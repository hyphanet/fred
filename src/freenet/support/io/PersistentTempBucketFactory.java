/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.crypt.RandomSource;
import freenet.support.Logger;

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

	/** Directory containing persistent temporary files */
	private final File dir;
	
	/** Original contents of directory */
	private HashSet originalFiles;
	
	/** Filename generator */
	private final FilenameGenerator fg;
	
	/** Random number generator */
	private final RandomSource rand;
	
	/** Buckets to free */
	private final LinkedList bucketsToFree;

	public PersistentTempBucketFactory(File dir, String prefix, RandomSource rand) throws IOException {
		this.dir = dir;
		this.rand = rand;
		this.fg = new FilenameGenerator(rand, false, dir, prefix);
		if(!dir.exists()) {
			dir.mkdir();
			if(!dir.exists()) {
				throw new IOException("Directory does not exist and cannot be created: "+dir);
			}
		}
		if(!dir.isDirectory())
			throw new IOException("Directory is not a directory: "+dir);
		originalFiles = new HashSet();
		File[] files = dir.listFiles();
		if((files != null) && (files.length > 0)) {
			for(int i=0;i<files.length;i++) {
				File f = files[i];
				String name = f.getName();
				if(f.isDirectory()) continue;
				if(!f.exists()) continue;
				if(!name.startsWith(prefix)) {
			        if(Logger.shouldLog(Logger.MINOR, this))
			        	Logger.minor(this, "Ignoring "+name);
					continue;
				}
				originalFiles.add(f);
			}
		}
		bucketsToFree = new LinkedList();
	}
	
	/**
	 * Called by a client to fetch the bucket denoted by a specific filename,
	 * and to register this fact so that it is not deleted on startup completion.
	 */
	public Bucket register(String filename) {
		File f = new File(dir, filename);
		Bucket b = new FileBucket(f, false, false, false, true);
		originalFiles.remove(f);
		return b;
	}
	
	public void register(File file) {
		originalFiles.remove(file);
	}
	
	/**
	 * Called when boot-up is complete.
	 * Deletes any old temp files still unclaimed.
	 */
	public void completedInit() {
		Iterator i = originalFiles.iterator();
		while(i.hasNext()) {
			File f = (File) (i.next());
			f.delete();
		}
	}

	public Bucket makeRawBucket(long size) throws IOException {
		return new FileBucket(fg.makeRandomFilename(), false, false, false, true);
	}

	public Bucket makeBucket(long size) throws IOException {
		Bucket b = makeRawBucket(size);
		return new PaddedEphemerallyEncryptedBucket(b, 1024, rand, false);
	}
	
	public Bucket makeEncryptedBucket() throws IOException {
		Bucket b = makeRawBucket(-1);
		return new PaddedEphemerallyEncryptedBucket(b, 1024, rand, false);
	}

	public Bucket registerEncryptedBucket(String filename, byte[] key, long len) throws IOException {
		Bucket fileBucket = register(filename);
		return new PaddedEphemerallyEncryptedBucket(fileBucket, 1024, len, key, rand);
	}
	
	/**
	 * Free an allocated bucket, but only after the change has been written to disk.
	 */
	public void freeBucket(Bucket b) throws IOException {
		synchronized(this) {
			bucketsToFree.add(b);
		}
	}

	public Bucket[] grabBucketsToFree() {
		synchronized(this) {
			return (Bucket[]) bucketsToFree.toArray(new Bucket[bucketsToFree.size()]);
		}
	}
	
	public File getDir() {
		return dir;
	}
}
