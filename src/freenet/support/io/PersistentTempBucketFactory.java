/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Random;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.client.async.DBJobRunner;
import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.node.Ticker;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * Handles persistent temp files. These are used for e.g. persistent downloads.
 * Fed a directory on startup.
 * Finds all files in the directory.
 * Keeps a list.
 * On startup, clients are expected to claim a temp bucket.
 * Once startup is completed, any unclaimed temp buckets which match the 
 * temporary file pattern will be deleted.
 */
public class PersistentTempBucketFactory implements BucketFactory, PersistentFileTracker {

	/** Original contents of directory */
	private HashSet<File> originalFiles;
	
	/** Filename generator */
	public final FilenameGenerator fg;
	
	/** Random number generator */
	private transient RandomSource strongPRNG;
	private transient Random weakPRNG;
	
	/** Buckets to free */
	private transient LinkedList<DelayedFreeBucket> bucketsToFree;
	
	private final long nodeDBHandle;
	
	private volatile boolean encrypt;
	
	private final PersistentBlobTempBucketFactory blobFactory;
	
	static final int BLOB_SIZE = CHKBlock.DATA_LENGTH;

	public PersistentTempBucketFactory(File dir, final String prefix, RandomSource strongPRNG, Random weakPRNG, boolean encrypt, long nodeDBHandle) throws IOException {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		blobFactory = new PersistentBlobTempBucketFactory(BLOB_SIZE, nodeDBHandle, new File(dir, "persistent-blob.tmp"));
		this.strongPRNG = strongPRNG;
		this.nodeDBHandle = nodeDBHandle;
		this.weakPRNG = weakPRNG;
		this.encrypt = encrypt;
		this.fg = new FilenameGenerator(weakPRNG, false, dir, prefix);
		if(!dir.exists()) {
			dir.mkdir();
			if(!dir.exists()) {
				throw new IOException("Directory does not exist and cannot be created: "+dir);
			}
		}
		if(!dir.isDirectory())
			throw new IOException("Directory is not a directory: "+dir);
		originalFiles = new HashSet<File>();
		File[] files = dir.listFiles(new FileFilter() {

			public boolean accept(File pathname) {
				if(!pathname.exists() || pathname.isDirectory())
					return false;
				String name = pathname.getName();
				if(name.startsWith(prefix))
					return true;
				return false;
			}
		});
		for(File f : files) {
			f = FileUtil.getCanonicalFile(f);
			if(logMINOR)
				Logger.minor(this, "Found " + f);
			originalFiles.add(f);
		}
		
		bucketsToFree = new LinkedList<DelayedFreeBucket>();
	}
	
	public void init(File dir, String prefix, RandomSource strongPRNG, Random weakPRNG) throws IOException {
		this.strongPRNG = strongPRNG;
		this.weakPRNG = weakPRNG;
		fg.init(dir, prefix, weakPRNG);
		bucketsToFree = new LinkedList<DelayedFreeBucket>();
	}
	
	public void register(File file) {
		synchronized(this) {
			if(originalFiles == null)
				throw new IllegalStateException("completed Init has already been called!");
			file = FileUtil.getCanonicalFile(file);
			if(!originalFiles.remove(file))
				Logger.error(this, "Preserving "+file+" but it wasn't found!", new Exception("error"));
		}
	}
	
	/**
	 * Called when boot-up is complete.
	 * Deletes any old temp files still unclaimed.
	 */
	public synchronized void completedInit() {
		// Persisting requests in the database means we don't register() files...
		// So keep all the temp files for now.
		// FIXME: tidy up unwanted temp files.
		
//		Iterator<File> i = originalFiles.iterator();
//		while(i.hasNext()) {
//			File f = (File) (i.next());
//			if(Logger.shouldLog(Logger.MINOR, this))
//				Logger.minor(this, "Deleting old tempfile "+f);
//			f.delete();
//		}
		originalFiles = null;
	}

	public Bucket makeBucket(long size) throws IOException {
		Bucket rawBucket = null;
		if(size == BLOB_SIZE) {
			rawBucket = blobFactory.makeBucket();
		}
		if(rawBucket == null)
			rawBucket = new PersistentTempFileBucket(fg.makeRandomFilename(), fg);
		Bucket maybeEncryptedBucket = (encrypt ? new PaddedEphemerallyEncryptedBucket(rawBucket, 1024, strongPRNG, weakPRNG) : rawBucket);
		return new DelayedFreeBucket(this, maybeEncryptedBucket);
	}

	/**
	 * Free an allocated bucket, but only after the change has been written to disk.
	 */
	public void delayedFreeBucket(DelayedFreeBucket b) {
		synchronized(this) {
			bucketsToFree.add(b);
		}
	}

	public LinkedList<DelayedFreeBucket> grabBucketsToFree() {
		synchronized(this) {
			LinkedList<DelayedFreeBucket> toFree = bucketsToFree;
			bucketsToFree = new LinkedList<DelayedFreeBucket>();
			return toFree;
		}
	}
	
	public File getDir() {
		return fg.getDir();
	}

	public FilenameGenerator getGenerator() {
		return fg;
	}

	public boolean matches(File file) {
		return fg.matches(file);
	}

	public long getID(File file) {
		return fg.getID(file);
	}
	
	public boolean isEncrypting() {
		return encrypt;
	}

	public static PersistentTempBucketFactory load(File dir, String prefix, RandomSource random, Random fastWeakRandom, ObjectContainer container, final long nodeDBHandle, boolean encrypt, DBJobRunner jobRunner, Ticker ticker) throws IOException {
		ObjectSet<PersistentTempBucketFactory> results = container.query(new Predicate<PersistentTempBucketFactory>() {
			public boolean match(PersistentTempBucketFactory factory) {
				if(factory.nodeDBHandle == nodeDBHandle) return true;
				return false;
			}
		});
		if(results.hasNext()) {
			PersistentTempBucketFactory factory = results.next();
			container.activate(factory, 5);
			factory.init(dir, prefix, random, fastWeakRandom);
			factory.setEncryption(encrypt);
			factory.blobFactory.onInit(container, jobRunner, fastWeakRandom, new File(dir, "persistent-blob.tmp"), BLOB_SIZE, ticker);
			return factory;
		} else {
			PersistentTempBucketFactory factory =
				new PersistentTempBucketFactory(dir, prefix, random, fastWeakRandom, encrypt, nodeDBHandle);
			factory.blobFactory.onInit(container, jobRunner, fastWeakRandom, new File(dir, "persistent-blob.tmp"), BLOB_SIZE, ticker);
			return factory;
		}
	}

	public void setEncryption(boolean encrypt) {
		this.encrypt = encrypt;
	}
}
