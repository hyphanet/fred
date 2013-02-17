/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.client.async.DatabaseDisabledException;
import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * Handles persistent temp files. These are used for e.g. persistent downloads. Anything of exactly 32KB
 * length will be stored in the blob file, otherwise these are simply temporary files in the directory 
 * specified for the PersistentFileTracker (which supports changing the directory, i.e. moving the files).
 * These temporary files are encrypted using an ephemeral key (unless the node is configured not to encrypt
 * temporary files as happens with physical security level LOW). Note that the files are only deleted *after*
 * the transaction containing their deletion reaches disk - so we should not leak temporary files, or 
 * forget that we deleted a bucket and try to reuse it, if there is an unclean shutdown.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS/
public class PersistentTempBucketFactory implements BucketFactory, PersistentFileTracker {

	/** Original contents of directory. This used to be used to delete any files that we can't account for.
	 * However at the moment we do not support garbage collection for non-blob persistent temp files. 
	 * When we implement it it will probably not use this structure... FIXME! */
	private HashSet<File> originalFiles;
	
	/** Filename generator. Tracks the directory and the prefix for temp files, can move them if these 
	 * change, generates filenames. */
	public final FilenameGenerator fg;
	
	/** Cryptographically strong random number generator */
	private transient RandomSource strongPRNG;
	/** Weak but fast random number generator. */
	private transient Random weakPRNG;
	
	/** Buckets to free. When buckets are freed, we write them to this list, and delete the files *after*
	 * the transaction recording the buckets being deleted hits the disk. */
	private final ArrayList<DelayedFreeBucket> bucketsToFree;
	
	/** The node database handle. Used to find everything for a specific node in the database. */
	private final long nodeDBHandle;

	/** Should we encrypt temporary files? */
	private volatile boolean encrypt;

	/** Any temporary file of exactly 32KB - and there are a lot of such temporary files! - will be allocated
	 * out of a single large blob file, whose contents are tracked in the database. */
	private final PersistentBlobTempBucketFactory blobFactory;
	
	static final int BLOB_SIZE = CHKBlock.DATA_LENGTH;
	
	/** Don't store the bucketsToFree unless it's been modified since we last stored it. */
	private transient boolean modifiedBucketsToFree;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/**
	 * Create a temporary bucket factory.
	 * @param dir Where to put it.
	 * @param prefix Prefix for temporary file names.
	 * @param strongPRNG Cryptographically strong random number generator, for making keys etc.
	 * @param weakPRNG Weak but fast random number generator.
	 * @param encrypt Whether to encrypt temporary files.
	 * @param nodeDBHandle The node database handle, used to find big objects in the database (such as the
	 * one and only PersistentTempBucketFactory).
	 * @throws IOException If we are unable to read the directory, etc.
	 */
	public PersistentTempBucketFactory(File dir, final String prefix, RandomSource strongPRNG, Random weakPRNG, boolean encrypt, long nodeDBHandle) throws IOException {
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

			@Override
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
		
		bucketsToFree = new ArrayList<DelayedFreeBucket>();
	}
	
	/** Re-initialise the bucket factory after restarting and pulling it from the database */
	public void init(File dir, String prefix, RandomSource strongPRNG, Random weakPRNG) throws IOException {
		this.strongPRNG = strongPRNG;
		this.weakPRNG = weakPRNG;
		fg.init(dir, prefix, weakPRNG);
	}
	
	/** Notify the bucket factory that a file is a temporary file, and not to be deleted. FIXME this is not
	 * currently used. @see #completedInit() */
	@Override
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
		
//		for(File f: originalFiles) {
//			if(Logger.shouldLog(LogLevel.MINOR, this))
//				Logger.minor(this, "Deleting old tempfile "+f);
//			f.delete();
//		}
		originalFiles = null;
	}

	/** Create a persistent temporary bucket. Use a blob if it is exactly 32KB, otherwise use a temporary
	 * file. Encrypted if appropriate. Wrapped in a DelayedFreeBucket so that they will not be deleted until
	 * after the transaction deleting them in the database commits. */
	@Override
	public Bucket makeBucket(long size) throws IOException {
		Bucket rawBucket = null;
		boolean mustWrap = true;
		if(size == BLOB_SIZE) {
			// No need for a DelayedFreeBucket, we handle this internally (and more efficiently) for blobs.
			mustWrap = false;
			try {
				rawBucket = blobFactory.makeBucket();
			} catch (DatabaseDisabledException e) {
				throw new IOException("Database disabled, persistent buckets not available");
			}
		}
		if(rawBucket == null)
			rawBucket = new PersistentTempFileBucket(fg.makeRandomFilename(), fg);
		if(encrypt)
			rawBucket = new PaddedEphemerallyEncryptedBucket(rawBucket, 1024, strongPRNG, weakPRNG);
		if(mustWrap)
			rawBucket = new DelayedFreeBucket(this, rawBucket);
		return rawBucket;
	}

	/**
	 * Free an allocated bucket, but only after the change has been written to disk.
	 */
	@Override
	public void delayedFreeBucket(DelayedFreeBucket b) {
		synchronized(this) {
			bucketsToFree.add(b);
			modifiedBucketsToFree = true;
		}
	}

	/** Get and clear the list of buckets to free after the transaction commits. */
	private DelayedFreeBucket[] grabBucketsToFree() {
		synchronized(this) {
			if(bucketsToFree.isEmpty()) return null;
			DelayedFreeBucket[] buckets = bucketsToFree.toArray(new DelayedFreeBucket[bucketsToFree.size()]);
			bucketsToFree.clear();
			modifiedBucketsToFree = true;
			return buckets;
		}
	}
	
	/** Get the directory we are creating temporary files in */
	@Override
	public File getDir() {
		return fg.getDir();
	}

	/** Get the FilenameGenerator */
	@Override
	public FilenameGenerator getGenerator() {
		return fg;
	}

	/** Is the file potentially one of ours? That is, is it in the right directory and does it have the
	 * right prefix? */
	@Override
	public boolean matches(File file) {
		return fg.matches(file);
	}

	/** Get the filename ID from the filename for a file that matches() */
	@Override
	public long getID(File file) {
		return fg.getID(file);
	}
	
	/** Are we encrypting temporary files? */
	public boolean isEncrypting() {
		return encrypt;
	}

	/** Load the persistent temporary bucket factory from the database, or create a new one if there is none
	 * in the database. Automatically migrate files if the dir or prefix change.
	 * @param dir The directory to put temporary files in.
	 * @param prefix The prefix for temporary files.
	 * @param random Strong random number generator.
	 * @param fastWeakRandom Weak PRNG.
	 * @param container The database. Must not be null, we must be running on the database thread and/or be
	 * initialising the node.
	 * @param nodeDBHandle The node database handle. Used to identify the specific PersistentTempBucketFactory
	 * for the current node. Hence it is possible to have multiple nodes share the same database, at least in theory.
	 * @param encrypt Whether to encrypt temporary buckets created. Note that if this is changed we do *not*
	 * decrypt/encrypt old buckets.
	 * @param jobRunner The DBJobRunner on which to schedule database jobs when needed.
	 * @param ticker The Ticker to run non-database jobs on.
	 * @return A persistent temporary bucket factory.
	 * @throws IOException If we cannot access the proposed directory, or some other I/O error prevents us
	 * using it.
	 */
	@SuppressWarnings("serial")
	public static PersistentTempBucketFactory load(File dir, String prefix, RandomSource random, Random fastWeakRandom, ObjectContainer container, final long nodeDBHandle, boolean encrypt, DBJobRunner jobRunner, Ticker ticker) throws IOException {
		ObjectSet<PersistentTempBucketFactory> results = container.query(new Predicate<PersistentTempBucketFactory>() {
			@Override
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

	/**
	 * Set whether to encrypt new persistent temp buckets. Note that we do not encrypt/decrypt old ones when
	 * this changes.
	 */
	public void setEncryption(boolean encrypt) {
		this.encrypt = encrypt;
	}

	/**
	 * Call this just before committing a transaction. Ensures that the list of buckets to free has been
	 * stored if necessary.
	 * @param db The database.
	 */
	public void preCommit(ObjectContainer db) {
		synchronized(this) {
			if(!modifiedBucketsToFree) return;
			modifiedBucketsToFree = false;
			for(DelayedFreeBucket bucket : bucketsToFree) {
				db.activate(bucket, 1);
				bucket.storeTo(db);
			}
			db.store(bucketsToFree);
		}
	}
	
	/**
	 * Call this just after committing a transaction. Deletes buckets pending deletion, and if there are lots
	 * of them, commits the transaction again.
	 * @param db The database.
	 */
	public void postCommit(ObjectContainer db) {
		blobFactory.postCommit();
		DelayedFreeBucket[] toFree = grabBucketsToFree();
		if(toFree == null || toFree.length == 0) return;
		int x = 0;
		for(DelayedFreeBucket bucket : toFree) {
			try {
				if(bucket.toFree())
					bucket.realFree();
				if(bucket.toRemove())
					bucket.realRemoveFrom(db);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t+" freeing bucket "+bucket+" after transaction commit", t);
			}
			x++;
		}
		if(x > 1024) {
			synchronized(this) {
				db.store(bucketsToFree);
			}
			// Lots of buckets freed, commit now to reduce memory footprint.
			db.commit();
		}
	}

	/**
	 * Add a callback job to be called when we are low on space in the blob temp bucket factory.
	 * For example a defragger, but there are other possibilities. 
	 */
	public void addBlobFreeCallback(DBJob job) {
		blobFactory.addBlobFreeCallback(job);
	}

	/**
	 * Remove a blob temp bucket factory callback job.
	 */
	public void removeBlobFreeCallback(DBJob job) {
		blobFactory.removeBlobFreeCallback(job);
	}
}
