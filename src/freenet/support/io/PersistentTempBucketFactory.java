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

import freenet.crypt.EncryptedRandomAccessBucket;
import freenet.crypt.MasterSecret;
import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.BucketFactory;
import freenet.support.api.RandomAccessBucket;

/**
 * Handles persistent temp files. These are used for e.g. persistent downloads. These are 
 * temporary files in the directory specified for the PersistentFileTracker (which supports 
 * changing the directory, i.e. moving the files).
 * 
 * These temporary files are encrypted using an ephemeral key (unless the node is configured not to encrypt
 * temporary files as happens with physical security level LOW). FIXME NO CRYPTO AT THE MOMENT. 
 * 
 * Note that the files are only deleted *after* the transaction containing their deletion reaches 
 * disk - so we should not leak temporary files, or forget that we deleted a bucket and try to 
 * reuse it, if there is an unclean shutdown.
 * 
 * PERSISTENCE: This class is involved in persistence but is not itself Serializable; it is 
 * recreated on every startup, and persistent Bucket's register themselves with it.
 */
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
	private final ArrayList<DelayedFree> bucketsToFree;

	private final Object encryptLock = new Object();
	/** Should we encrypt temporary files? */
	private boolean encrypt;
    private MasterSecret secret;
	
	private DiskSpaceChecker checker;
	
	
	private long commitID;

	static final int BLOB_SIZE = CHKBlock.DATA_LENGTH;
	
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
	 * @throws IOException If we are unable to read the directory, etc.
	 */
	public PersistentTempBucketFactory(File dir, final String prefix, RandomSource strongPRNG, Random weakPRNG, boolean encrypt) throws IOException {
		this.strongPRNG = strongPRNG;
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
		
		bucketsToFree = new ArrayList<DelayedFree>();
		commitID = 1; // Must start > 0.
	}
	
	public void setDiskSpaceChecker(DiskSpaceChecker checker) {
	    this.checker = checker;
	}
	
	public void setMasterSecret(MasterSecret secret) {
	    synchronized(encryptLock) {
	        this.secret = secret;
	    }
	}
	
	/** Notify the bucket factory that a file is a temporary file, and not to be deleted. FIXME this is not
	 * currently used. @see #completedInit() */
	@Override
	public void register(File file) {
		synchronized(this) {
			if(originalFiles == null)
				throw new IllegalStateException("completed Init has already been called!");
			file = FileUtil.getCanonicalFile(file);
			if(logMINOR) Logger.minor(this, "Preserving "+file, new Exception("debug"));
			if(!originalFiles.remove(file))
				Logger.error(this, "Preserving "+file+" but it wasn't found!", new Exception("error"));
		}
	}
	
	/**
	 * Called when boot-up is complete.
	 * Deletes any old temp files still unclaimed.
	 */
	public synchronized void completedInit() {
	    if(originalFiles == null) {
	        Logger.error(this, "Completed init called twice", new Exception("error"));
	        return;
	    }
		for(File f: originalFiles) {
			if(Logger.shouldLog(LogLevel.MINOR, this))
				Logger.minor(this, "Deleting old tempfile "+f);
			f.delete();
		}
		originalFiles = null;
	}

	/** Create a persistent temporary bucket. Encrypted if appropriate. Wrapped in a 
	 * DelayedFreeBucket so that they will not be deleted until after the transaction deleting 
	 * them in the database commits. */
	@Override
	public RandomAccessBucket makeBucket(long size) throws IOException {
		RandomAccessBucket rawBucket = null;
		boolean mustWrap = true;
		if(rawBucket == null)
			rawBucket = new PersistentTempFileBucket(fg.makeRandomFilename(), fg, this);
		synchronized(encryptLock) {
		    if(encrypt) {
                rawBucket = new PaddedRandomAccessBucket(rawBucket);
		        rawBucket = new EncryptedRandomAccessBucket(TempBucketFactory.CRYPT_TYPE, 
		                rawBucket, secret);
		    }
		}
		if(mustWrap)
			rawBucket = new DelayedFreeRandomAccessBucket(this, rawBucket);
		return rawBucket;
	}

	/**
	 * Free an allocated bucket, but only after the change has been written to disk.
	 */
	@Override
	public void delayedFree(DelayedFree b, long createdCommitID) {
		synchronized(this) {
			if(createdCommitID != commitID) {
			    bucketsToFree.add(b);
			    return;
			}
		}
		b.realFree();
	}

    /** Returns a list of buckets to free. The caller should write the buckets to the checkpoint, 
     * and free them after the checkpoint has written successfully, by calling postCommit(). */
	public DelayedFree[] grabBucketsToFree() {
		synchronized(this) {
			if(bucketsToFree.isEmpty()) return null;
			DelayedFree[] buckets = bucketsToFree.toArray(new DelayedFree[bucketsToFree.size()]);
			bucketsToFree.clear();
			commitID++;
			return buckets;
		}
	}
	
    @Override
    public synchronized long commitID() {
        return commitID;
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

	/** Are we encrypting temporary files? */
	public boolean isEncrypting() {
	    synchronized(encryptLock) {
	        return encrypt;
	    }
	}

	/**
	 * Set whether to encrypt new persistent temp buckets. Note that we do not encrypt/decrypt old ones when
	 * this changes.
	 */
	public void setEncryption(boolean encrypt) {
	    synchronized(encryptLock) {
	        this.encrypt = encrypt;
	    }
	}

	/**
	 * Delete the buckets.
	 */
	public void finishDelayedFree(DelayedFree[] buckets) {
	    if(buckets != null) {
	        for(DelayedFree bucket : buckets) {
	            try {
	                if(bucket.toFree())
	                    bucket.realFree();
	            } catch (Throwable t) {
	                Logger.error(this, "Caught "+t+" freeing bucket "+bucket+" after transaction commit", t);
	            }
	        }
	    }
	}

    @Override
    public boolean checkDiskSpace(File file, int toWrite, int bufferSize) {
        return checker.checkDiskSpace(file, toWrite, bufferSize);
    }

}
