/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;
import freenet.support.Executor;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import java.util.ArrayList;

/**
 * Temporary Bucket Factory
 * 
 * Buckets created by this factory can be either:
 *	- ArrayBuckets
 * OR
 *	- FileBuckets
 * 
 * ArrayBuckets are used if and only if:
 *	1) there is enough room remaining on the pool (@see maxRamUsed and @see bytesInUse)
 *	2) the initial size is smaller than (@maxRAMBucketSize)
 * 
 * Depending on how they are used they might switch from one type to another transparently.
 * 
 * Currently they are two factors considered for a migration:
 *	- if they are long-lived or not (@see RAMBUCKET_MAX_AGE)
 *	- if their size is over RAMBUCKET_CONVERSION_FACTOR*maxRAMBucketSize
 */
public class TempBucketFactory implements BucketFactory {
	public final static long defaultIncrement = 4096;
	public final static float DEFAULT_FACTOR = 1.25F;
	
	private final FilenameGenerator filenameGenerator;
	private long bytesInUse = 0;
	private final RandomSource strongPRNG;
	private final Random weakPRNG;
	private final Executor executor;
	private volatile boolean reallyEncrypt;
	
	/** How big can the defaultSize be for us to consider using RAMBuckets? */
	private long maxRAMBucketSize;
	/** How much memory do we dedicate to the RAMBucketPool? (in bytes) */
	private long maxRamUsed;

	/** How old is a long-lived RAMBucket? */
	private final static int RAMBUCKET_MAX_AGE = 5*60*1000; // 5mins
	/** How many times the maxRAMBucketSize can a RAMBucket be before it gets migrated? */
	final static int RAMBUCKET_CONVERSION_FACTOR = 4;
	
	final static boolean TRACE_BUCKET_LEAKS = false;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public class TempBucket implements Bucket {
		/** The underlying bucket itself */
		private Bucket currentBucket;
		/** We have to account the size of the underlying bucket ourself in order to be able to access it fast */
		private long currentSize;
		/** Has an OutputStream been opened at some point? */
		private boolean hasWritten;
		/** A link to the "real" underlying outputStream, even if we migrated */
		private OutputStream os = null;
		/** All the open-streams to reset or close on migration or free() */
		private final ArrayList<TempBucketInputStream> tbis;
		/** An identifier used to know when to deprecate the InputStreams */
		private short osIndex;
		/** A timestamp used to evaluate the age of the bucket and maybe consider it for a migration */
		public final long creationTime;
		private boolean hasBeenFreed = false;
		
		private final Throwable tracer;
		
		public TempBucket(long now, Bucket cur) {
			if(cur == null)
				throw new NullPointerException();
			if (TRACE_BUCKET_LEAKS)
				tracer = new Throwable();
			else
				tracer = null;
			this.currentBucket = cur;
			this.creationTime = now;
			this.osIndex = 0;
			this.tbis = new ArrayList<TempBucketInputStream>(1);
			if(logMINOR) Logger.minor(TempBucket.class, "Created "+this, new Exception("debug"));
		}
		
		private synchronized void closeInputStreams(boolean forFree) {
			for(ListIterator<TempBucketInputStream> i = tbis.listIterator(); i.hasNext();) {
				TempBucketInputStream is = i.next();
					if(forFree) {
						i.remove();
						try {
							is.close();
						} catch (IOException e) {
							Logger.error(this, "Caught "+e+" closing "+is);
						}
					} else {
						try {
							is._maybeResetInputStream();
						} catch(IOException e) {
							i.remove();
							Closer.close(is);
						}
					}
			}
		}
		
		/** A blocking method to force-migrate from a RAMBucket to a FileBucket */
		final void migrateToFileBucket() throws IOException {
			Bucket toMigrate = null;
			long size;
			synchronized(this) {
				if(!isRAMBucket() || hasBeenFreed)
					// Nothing to migrate! We don't want to switch back to ram, do we?					
					return;
				toMigrate = currentBucket;
				Bucket tempFB = _makeFileBucket();
				size = currentSize;
				if(os != null) {
					os.flush();
					os.close();
					// DO NOT INCREMENT THE osIndex HERE!
					os = tempFB.getOutputStream();
					if(size > 0)
						BucketTools.copyTo(toMigrate, os, size);
				} else {
					if(size > 0) {
						OutputStream temp = tempFB.getOutputStream();
						try {
						BucketTools.copyTo(toMigrate, temp, size);
						} finally {
						temp.close();
						}
					}
				}
				if(toMigrate.isReadOnly())
					tempFB.setReadOnly();
				
				closeInputStreams(false);
				
				currentBucket = tempFB;
				// We need streams to be reset to point to the new bucket
			}
			if(logMINOR)
				Logger.minor(this, "We have migrated "+toMigrate.hashCode());
			
			synchronized(ramBucketQueue) {
				ramBucketQueue.remove(getReference());
			}
			
			// We can free it on-thread as it's a rambucket
			toMigrate.free();
			// Might have changed already so we can't rely on currentSize!
			_hasFreed(size);
		}
		
		public synchronized final boolean isRAMBucket() {
			return (currentBucket instanceof ArrayBucket);
		}

		@Override
		public synchronized OutputStream getOutputStream() throws IOException {
			if(osIndex > 0)
				throw new IOException("Only one OutputStream per bucket on "+this+" !");
			// Hence we don't need to reset currentSize / _hasTaken() if a bucket is reused.
			// FIXME we should migrate to disk rather than throwing.
			hasWritten = true;
			OutputStream tos = new TempBucketOutputStream(++osIndex);
			if(logMINOR)
				Logger.minor(this, "Got "+tos+" for "+this, new Exception());
			return tos;
		}

		private class TempBucketOutputStream extends OutputStream {
			boolean closed = false;
			TempBucketOutputStream(short idx) throws IOException {
				if(os == null)
					os = currentBucket.getOutputStream();
			}
			
			private void _maybeMigrateRamBucket(long futureSize) throws IOException {
				if(closed) throw new IOException("Already closed");
				if(isRAMBucket()) {
					boolean shouldMigrate = false;
					boolean isOversized = false;
					
					if(futureSize >= Math.min(Integer.MAX_VALUE, maxRAMBucketSize * RAMBUCKET_CONVERSION_FACTOR)) {
						isOversized = true;
						shouldMigrate = true;
					} else if ((futureSize - currentSize) + bytesInUse >= maxRamUsed)
						shouldMigrate = true;
					
					if(shouldMigrate) {
						if(logMINOR) {
							if(isOversized)
								Logger.minor(this, "The bucket "+TempBucket.this+" is over "+SizeUtil.formatSize(maxRAMBucketSize*RAMBUCKET_CONVERSION_FACTOR)+": we will force-migrate it to disk.");
							else
								Logger.minor(this, "The bucketpool is full: force-migrate before we go over the limit");
						}
						migrateToFileBucket();
					}
				}
			}
			
			@Override
			public final void write(int b) throws IOException {
				synchronized(TempBucket.this) {
					long futureSize = currentSize + 1;
					_maybeMigrateRamBucket(futureSize);
					os.write(b);
					currentSize = futureSize;
					if(isRAMBucket()) // We need to re-check because it might have changed!
						_hasTaken(1);
				}
			}
			
			@Override
			public final void write(byte b[], int off, int len) throws IOException {
				synchronized(TempBucket.this) {
					long futureSize = currentSize + len;
					_maybeMigrateRamBucket(futureSize);
					os.write(b, off, len);
					currentSize = futureSize;
					if(isRAMBucket()) // We need to re-check because it might have changed!
						_hasTaken(len);
				}
			}
			
			@Override
			public final void flush() throws IOException {
				synchronized(TempBucket.this) {
					_maybeMigrateRamBucket(currentSize);
					if(!closed)
						os.flush();
				}
			}
			
			@Override
			public final void close() throws IOException {
				synchronized(TempBucket.this) {
					if(closed) return;
					_maybeMigrateRamBucket(currentSize);
					os.flush();
					os.close();
					os = null;
					closed = true;
				}
			}
		}

		@Override
		public synchronized InputStream getInputStream() throws IOException {
			if(!hasWritten)
				throw new IOException("No OutputStream has been openned! Why would you want an InputStream then?");
			TempBucketInputStream is = new TempBucketInputStream(osIndex);
			tbis.add(is);
			if(logMINOR)
				Logger.minor(this, "Got "+is+" for "+this, new Exception());
			return is;
		}
		
		private class TempBucketInputStream extends InputStream {
			/** The current InputStream we use from the underlying bucket */
			private InputStream currentIS;
			/** Keep a counter to know where we are on the stream (useful when we have to reset and skip) */
			private long index = 0;
			/** Will change if a new OutputStream is openned: used to detect deprecation */
			private final short idx;
			
			TempBucketInputStream(short idx) throws IOException {
				this.idx = idx;
				this.currentIS = currentBucket.getInputStream();
			}
			
			public void _maybeResetInputStream() throws IOException {
				if(idx != osIndex)
					close();
				else {
					Closer.close(currentIS);
					currentIS = currentBucket.getInputStream();
					long toSkip = index;
					while(toSkip > 0) {
						toSkip -= currentIS.skip(toSkip);
					}
				}
			}
			
			@Override
			public final int read() throws IOException {
				synchronized(TempBucket.this) {
					int toReturn = currentIS.read();
					if(toReturn != -1)
						index++;
					return toReturn;
				}
			}
			
			@Override
			public int read(byte b[]) throws IOException {
				synchronized(TempBucket.this) {
					return read(b, 0, b.length);
				}
			}
			
			@Override
			public int read(byte b[], int off, int len) throws IOException {
				synchronized(TempBucket.this) {
					int toReturn = currentIS.read(b, off, len);
					if(toReturn > 0)
						index += toReturn;
					return toReturn;
				}
			}
			
			@Override
			public long skip(long n) throws IOException {
				synchronized(TempBucket.this) {
					long skipped = currentIS.skip(n);
					index += skipped;
					return skipped;
				}
			}
			
			@Override
			public int available() throws IOException {
				synchronized(TempBucket.this) {
					return currentIS.available();
				}
			}
			
			@Override
			public boolean markSupported() {
				return false;
			}
			
			@Override
			public final void close() throws IOException {
				synchronized(TempBucket.this) {
					Closer.close(currentIS);
					tbis.remove(this);
				}
			}
		}

		@Override
		public synchronized String getName() {
			return currentBucket.getName();
		}

		@Override
		public synchronized long size() {
			return currentSize;
		}

		@Override
		public synchronized boolean isReadOnly() {
			return currentBucket.isReadOnly();
		}

		@Override
		public synchronized void setReadOnly() {
			currentBucket.setReadOnly();
		}

		@Override
		public synchronized void free() {
			if(hasBeenFreed) return;
			hasBeenFreed = true;
			
			Closer.close(os);
			closeInputStreams(true);
			currentBucket.free();
			if(isRAMBucket()) {
				_hasFreed(currentSize);
				synchronized(ramBucketQueue) {
					ramBucketQueue.remove(getReference());
				}
			}
		}

		@Override
		public Bucket createShadow() {
			return currentBucket.createShadow();
		}

		@Override
		public void removeFrom(ObjectContainer container) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void storeTo(ObjectContainer container) {
			throw new UnsupportedOperationException();
		}
		
		public boolean objectCanNew(ObjectContainer container) {
			Logger.error(this, "Not storing TempBucket in database", new Exception("error"));
			throw new IllegalStateException();
		}
		
		public boolean objectCanUpdate(ObjectContainer container) {
			Logger.error(this, "Trying to store a TempBucket!", new Exception("error"));
			throw new IllegalStateException();
		}
		
		public boolean objectCanActivate(ObjectContainer container) {
			Logger.error(this, "Trying to store a TempBucket!", new Exception("error"));
			return false;
		}
		
		public boolean objectCanDeactivate(ObjectContainer container) {
			Logger.error(this, "Trying to store a TempBucket!", new Exception("error"));
			return false;
		}
		
		private WeakReference<TempBucket> weakRef = new WeakReference<TempBucket>(this);

		public WeakReference<TempBucket> getReference() {
			return weakRef;
		}
		
		@Override
		protected void finalize() throws Throwable {
			if (!hasBeenFreed) {
				if (TRACE_BUCKET_LEAKS)
					Logger.error(this, "TempBucket not freed, size=" + size() + ", isRAMBucket=" + isRAMBucket()+" : "+this, tracer);
				free();
			}
                        super.finalize();
		}
	}
	
	// Storage accounting disabled by default.
	public TempBucketFactory(Executor executor, FilenameGenerator filenameGenerator, long maxBucketSizeKeptInRam, long maxRamUsed, RandomSource strongPRNG, Random weakPRNG, boolean reallyEncrypt) {
		this.filenameGenerator = filenameGenerator;
		this.maxRamUsed = maxRamUsed;
		this.maxRAMBucketSize = maxBucketSizeKeptInRam;
		this.strongPRNG = strongPRNG;
		this.weakPRNG = weakPRNG;
		this.reallyEncrypt = reallyEncrypt;
		this.executor = executor;
	}

	@Override
	public Bucket makeBucket(long size) throws IOException {
		return makeBucket(size, DEFAULT_FACTOR, defaultIncrement);
	}

	public Bucket makeBucket(long size, float factor) throws IOException {
		return makeBucket(size, factor, defaultIncrement);
	}
	
	private synchronized void _hasTaken(long size) {
		bytesInUse += size;
	}
	
	private synchronized void _hasFreed(long size) {
		bytesInUse -= size;
	}
	
	public synchronized long getRamUsed() {
		return bytesInUse;
	}
	
	public synchronized void setMaxRamUsed(long size) {
		maxRamUsed = size;
	}
	
	public synchronized long getMaxRamUsed() {
		return maxRamUsed;
	}
	
	public synchronized void setMaxRAMBucketSize(long size) {
		maxRAMBucketSize = size;
	}
	
	public synchronized long getMaxRAMBucketSize() {
		return maxRAMBucketSize;
	}
	
	public void setEncryption(boolean value) {
		reallyEncrypt = value;
	}
	
	public boolean isEncrypting() {
		return reallyEncrypt;
	}

	static final double MAX_USAGE_LOW = 0.8;
	static final double MAX_USAGE_HIGH = 0.9;
	
	/**
	 * Create a temp bucket
	 * 
	 * @param size
	 *            Default size
	 * @param factor
	 *            Factor to increase size by when need more space
	 * @return A temporary Bucket
	 * @exception IOException
	 *                If it is not possible to create a temp bucket due to an
	 *                I/O error
	 */
	public TempBucket makeBucket(long size, float factor, long increment) throws IOException {
		Bucket realBucket = null;
		boolean useRAMBucket = false;
		long now = System.currentTimeMillis();
		
		synchronized(this) {
			if((size > 0) && (size <= maxRAMBucketSize) && (bytesInUse < maxRamUsed) && (bytesInUse + size <= maxRamUsed)) {
				useRAMBucket = true;
			}
			if(bytesInUse >= maxRamUsed * MAX_USAGE_HIGH && !runningCleaner) {
				runningCleaner = true;
				executor.execute(cleaner);
			}
		}
		
		// Do we want a RAMBucket or a FileBucket?
		realBucket = (useRAMBucket ? new ArrayBucket() : _makeFileBucket());
		
		TempBucket toReturn = new TempBucket(now, realBucket);
		if(useRAMBucket) { // No need to consider them for migration if they can't be migrated
			synchronized(ramBucketQueue) {
				ramBucketQueue.add(toReturn.getReference());
			}
		}
		return toReturn;
}
	
	boolean runningCleaner = false;
	
	private final Runnable cleaner = new Runnable() {

		@Override
		public void run() {
			try {
				long now = System.currentTimeMillis();
				// First migrate all the old buckets.
				cleanBucketQueue(now, false);
				while(true) {
					// Now migrate buckets until usage is below the lower threshold.
					synchronized(TempBucketFactory.this) {
						if(bytesInUse <= maxRamUsed * MAX_USAGE_LOW) return;
					}
					if(!cleanBucketQueue(System.currentTimeMillis(), true)) return;
				}
			} finally {
				synchronized(TempBucketFactory.this) {
					runningCleaner = false;
				}
			}
		}
		
	};
	
	/** Migrate all long-lived buckets from the queue.
	 * @param now The current time (System.currentTimeMillis()).
	 * @param force If true, migrate one bucket which isn't necessarily long lived, 
	 * just to free up space. Otherwise we will migrate all long-lived buckets but
	 * not any others. 
	 * @return True if we migrated any buckets.
	 */
	private boolean cleanBucketQueue(long now, boolean force) {
		boolean shouldContinue = true;
		// create a new list to avoid race-conditions
		Queue<TempBucket> toMigrate = null;
                if(logMINOR)
			Logger.minor(this, "Starting cleanBucketQueue");
		do {
			synchronized(ramBucketQueue) {
				final WeakReference<TempBucket> tmpBucketRef = ramBucketQueue.peek();
				if (tmpBucketRef == null)
					shouldContinue = false;
				else {
					TempBucket tmpBucket = tmpBucketRef.get();
					if (tmpBucket == null) {
						ramBucketQueue.remove(tmpBucketRef);
						continue; // ugh. this is freed
					}

					// Don't access the buckets inside the lock, will deadlock.
					if (tmpBucket.creationTime + RAMBUCKET_MAX_AGE > now && !force)
						shouldContinue = false;
					else {
						if (logMINOR)
							Logger.minor(this, "The bucket "+tmpBucket+" is " + TimeUtil.formatTime(now - tmpBucket.creationTime)
							        + " old: we will force-migrate it to disk.");
						ramBucketQueue.remove(tmpBucketRef);
						if(toMigrate == null) toMigrate = new LinkedList<TempBucket>();
						toMigrate.add(tmpBucket);
						force = false;
					}
				}
			}
		} while(shouldContinue);

		if(toMigrate == null) return false;
		if(toMigrate.size() > 0) {
			if(logMINOR)
				Logger.minor(this, "We are going to migrate " + toMigrate.size() + " RAMBuckets");
			for(TempBucket tmpBucket : toMigrate) {
				try {
					tmpBucket.migrateToFileBucket();
				} catch(IOException e) {
					Logger.error(tmpBucket, "An IOE occured while migrating long-lived buckets:" + e.getMessage(), e);
				}
			}
			return true;
		}
		return false;
	}
	
	private final Queue<WeakReference<TempBucket>> ramBucketQueue = new LinkedBlockingQueue<WeakReference<TempBucket>>();
	
	private Bucket _makeFileBucket() {
		Bucket fileBucket = new TempFileBucket(filenameGenerator.makeRandomFilename(), filenameGenerator, true);
		// Do we want it to be encrypted?
		return (reallyEncrypt ? new PaddedEphemerallyEncryptedBucket(fileBucket, 1024, strongPRNG, weakPRNG) : fileBucket);
	}
}
