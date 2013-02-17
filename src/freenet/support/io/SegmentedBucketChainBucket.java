package freenet.support.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.client.async.DatabaseDisabledException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * Splits a large persistent file into a series of buckets, which are collected 
 * into groups called segments to avoid huge transactions/memory usage.
 * 
 * NON-PERSISTENT: This class uses persistent buckets, it stores them in the 
 * database to save memory, but the bucket itself does not support persistence.
 * Making it support persistence cleanly requires major refactoring. The obvious
 * avenues are:
 * 
 * 1. Pass DBJobRunner in to getOutputStream(), getInputStream(), free(), 
 * storeTo(). This will touch hundreds of files, mostly it is trivial though. It
 * will however increase the overhead for all buckets slightly.
 * 2. Make DBJobRunner a static variable, hence there will be only one database
 * thread for the whole VM, even in a simulation with more than one node. One
 * difficulty with this is that the node.db4o needs to be in the correct directory,
 * yet the database would need to be initiated very early on.
 * 
 * Generally we create it, write to it, call getBuckets() and clear(), anyway ...
 * 
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class SegmentedBucketChainBucket implements NotPersistentBucket {

	private final ArrayList<SegmentedChainBucketSegment> segments;
	private boolean readOnly;
	public final long bucketSize;
	public final int segmentSize;
	private long size;
	private boolean freed;
	final BucketFactory bf;
	private transient DBJobRunner dbJobRunner;
	private final boolean cacheWholeBucket;

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
	 * Create a segmented bucket chain bucket. This is a chain of buckets. The chain
	 * is divided into segments, stored in the database, so we can have an arbitrarily
	 * large bucket.
	 * @param blockSize How big should the individual buckets be? Normally this is 32768.
	 * @param factory Factory to create the individual buckets. Normally this is a
	 * PersistentBlobTempBucketFactory.
	 * @param runner The database job runner. We need this to persist the segments.
	 * We don't access the database except when moving between segments and closing
	 * the file.
	 * @param segmentSize2 How many blocks in a segment.
	 * @param cacheWholeBucket If true, we will minimise small writes and seeking
	 * by caching a whole 32KB sub-bucket at once. This should improve performance
	 * on spinning disks, flash disks, RAID arrays and everything else, at the cost
	 * of a buffer (on write) of size bucketSize. 
	 */
	public SegmentedBucketChainBucket(int blockSize, BucketFactory factory, 
			DBJobRunner runner, int segmentSize2, boolean cacheWholeBucket) {
		bucketSize = blockSize;
		bf = factory;
		dbJobRunner = runner;
		segmentSize = segmentSize2;
		segments = new ArrayList<SegmentedChainBucketSegment>();
		this.cacheWholeBucket = cacheWholeBucket;
	}

	@Override
	public Bucket createShadow() {
		return null;
	}

	@Override
	public void free() {
		synchronized(this) {
			freed = true;
			clearing = false;
		}
		
		// Due to memory issues, we cannot complete the cleanup before returning, especially if we are already on the database thread...
		DBJob freeJob = new DBJob() {
			
			@Override
			public boolean run(ObjectContainer container, ClientContext context) {
				SegmentedChainBucketSegment segment = null;
				if(!container.ext().isStored(SegmentedBucketChainBucket.this)) {
					Logger.error(this, "Bucket not stored in freeJob, already deleted???");
					container.delete(this);
					return true;
				}
				synchronized(this) {
					if(!segments.isEmpty())
						segment = segments.remove(0);
				}
				if(segment != null) {
					container.activate(segment, 1);
					if(logMINOR)
						Logger.minor(SegmentedBucketChainBucket.this, "Freeing segment "+segment);
					segment.activateBuckets(container);
					segment.free();
					segment.removeFrom(container);
					synchronized(this) {
						if(!segments.isEmpty()) {
							try {
								dbJobRunner.queue(this, NativeThread.HIGH_PRIORITY, true);
								dbJobRunner.queueRestartJob(this, NativeThread.HIGH_PRIORITY, container, false);
							} catch (DatabaseDisabledException e) {
								// Impossible
							}
							container.store(this);
							return true;
						}
					}
				}
				container.delete(segments);
				container.delete(SegmentedBucketChainBucket.this);
				container.delete(this);
				synchronized(SegmentedBucketChainBucket.this) {
					if(killMe == null) return true;
				}
				try {
					dbJobRunner.removeRestartJob(killMe, NativeThread.HIGH_PRIORITY, container);
				} catch (DatabaseDisabledException e) {
					// Impossible
				}
				container.delete(killMe);
				return true;
			}
			
		};
		
		// Must be run blocking so that if we are on the database thread, the job is
		// added before committing. If we are not on the database thread, it doesn't
		// matter.
		try {
			dbJobRunner.runBlocking(freeJob, NativeThread.HIGH_PRIORITY);
		} catch (DatabaseDisabledException e) {
			// Impossible
			Logger.error(this, "Unable to free "+this+" because database is disabled!");
		}
	}

	@Override
	public InputStream getInputStream() throws IOException {
		synchronized(this) {
			if(freed || clearing) throw new IOException("Freed");
		}
		return new InputStream() {

			int segmentNo = -1;
			int bucketNo = segmentSize;
			SegmentedChainBucketSegment seg = null;
			Bucket[] buckets = null;
			InputStream is = null;
			private long bucketRead = 0;
			private boolean closed;
			// FIXME implement cacheWholeBucket for InputStream too.
			// Reason this hasn't been done is I don't think we actually use SBCB.getInputStream(), at least not much.
			
			@Override
			public int read() throws IOException {
				byte[] b = new byte[1];
				if(read(b, 0, 1) <= 0) return -1;
				return b[0];
			}
			
			@Override
			public int read(byte[] buf) throws IOException {
				return read(buf, 0, buf.length);
			}
			
			@Override
			public int read(byte[] buf, int offset, int length) throws IOException {
				if(closed) throw new IOException("Already closed");
				if(bucketRead == bucketSize || is == null) {
					if(is != null)
						is.close();
					if(buckets != null)
						buckets[bucketNo] = null;
					bucketRead = 0;
					bucketNo++;
					if(bucketNo == segmentSize || buckets == null) {
						bucketNo = 0;
						segmentNo++;
						seg = getSegment(segmentNo);
						if(seg == null) return -1;
						try {
							buckets = getBuckets(seg);
						} catch (DatabaseDisabledException e) {
							throw new IOException("Database disabled during read!");
						}
					}
					if(bucketNo >= buckets.length) {
						synchronized(SegmentedBucketChainBucket.this) {
							if(segmentNo >= segments.size())
								// No more data
								return -1;
						}
						try {
							buckets = getBuckets(seg);
						} catch (DatabaseDisabledException e) {
							throw new IOException("Database disabled during read!");
						}
						if(bucketNo >= buckets.length)
							return -1;
					}
					is = buckets[bucketNo].getInputStream();
				}
				int r = is.read(buf, offset, length);
				if(r > 0)
					bucketRead += r;
				return r;
			}
			
			@Override
			public void close() throws IOException {
				if(closed) return;
				if(is != null) is.close();
				closed = true;
				is = null;
				seg = null;
				buckets = null;
			}
			
		};
	}

	protected synchronized SegmentedChainBucketSegment getSegment(int i) {
		return segments.get(i);
	}

	protected Bucket[] getBuckets(final SegmentedChainBucketSegment seg) throws DatabaseDisabledException {
		final BucketArrayWrapper baw = new BucketArrayWrapper();
		dbJobRunner.runBlocking(new DBJob() {

			@Override
			public boolean run(ObjectContainer container, ClientContext context) {
				container.activate(seg, 1);
				synchronized(baw) {
					baw.buckets = seg.shallowCopyBuckets();
				}
				container.deactivate(seg, 1);
				return false;
			}
			
		}, NativeThread.HIGH_PRIORITY);
		synchronized(baw) {
			return baw.buckets;
		}
	}

	@Override
	public String getName() {
		return "SegmentedBucketChainBucket";
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		final SegmentedChainBucketSegment[] segs;
		synchronized(this) {
			if(readOnly) throw new IOException("Read-only");
			if(freed || clearing) throw new IOException("Freed");
			size = 0;
			segs = segments.toArray(new SegmentedChainBucketSegment[segments.size()]);
			segments.clear();
		}
		for(SegmentedChainBucketSegment seg: segs)
			seg.free();
		if(segs.length > 0) {
			try {
				dbJobRunner.runBlocking(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						for(SegmentedChainBucketSegment seg: segs)
							seg.removeFrom(container);
						return true;
					}
					
				}, NativeThread.HIGH_PRIORITY);
			} catch (DatabaseDisabledException e) {
				throw new IOException("Database disabled");
			}
		}
		return new OutputStream() {
			
			int segmentNo = 0;
			int bucketNo = 0;
			SegmentedChainBucketSegment seg = makeSegment(segmentNo, null);
			OutputStream cur;
			private long bucketLength;
			private boolean closed;
			private ByteArrayOutputStream baos;
			{
				if(cacheWholeBucket)
					baos = new ByteArrayOutputStream((int)bucketSize);
				else
					cur = seg.makeBucketStream(bucketNo, SegmentedBucketChainBucket.this);
			}

			@Override
			public void write(int arg0) throws IOException {
				write(new byte[] { (byte)arg0 });
			}
			
			@Override
			public void write(byte[] buf) throws IOException {
				write(buf, 0, buf.length);
			}
			
			@Override
			public void write(byte[] buf, int offset, int length) throws IOException {
				boolean ro;
				synchronized(SegmentedBucketChainBucket.this) {
					ro = readOnly;
				}
				if(ro) {
					if(!closed) close();
					throw new IOException("Read-only");
				}
				if(closed) throw new IOException("Already closed");
				while(length > 0) {
					if(bucketLength == bucketSize) {
						if(bucketNo == segmentSize) {
							bucketNo = 0;
							segmentNo++;
							seg = makeSegment(segmentNo, seg);
						}
						if(baos != null) {
							OutputStream os = seg.makeBucketStream(bucketNo, SegmentedBucketChainBucket.this);
							bucketNo++;
							try {
							os.write(baos.toByteArray());
							baos.reset();
							} finally {
							os.close();
							}
						} else {
							cur.close();
							cur = seg.makeBucketStream(++bucketNo, SegmentedBucketChainBucket.this);
						}
						bucketLength = 0;
					}
					int left = (int)Math.min(Integer.MAX_VALUE, bucketSize - bucketLength);
					int write = Math.min(left, length);
					if(baos != null)
						baos.write(buf, offset, write);
					else
						cur.write(buf, offset, write);
					offset += write;
					length -= write;
					bucketLength += write;
					synchronized(SegmentedBucketChainBucket.class) {
						size += write;
					}
				}					
			}
			
			@Override
			public void close() throws IOException {
				if(closed) return;
				if(logMINOR)
					Logger.minor(this, "Closing "+this+" for "+SegmentedBucketChainBucket.this);
				if(baos != null && baos.size() > 0) {
					if(bucketNo == segmentSize) {
						bucketNo = 0;
						segmentNo++;
						seg = makeSegment(segmentNo, seg);
					}
					OutputStream os = seg.makeBucketStream(bucketNo, SegmentedBucketChainBucket.this);
					bucketNo++;
					try {
					os.write(baos.toByteArray());
					baos.reset();
					} finally {
					os.close();
					}
				} else {
					cur.close();
				}
				closed = true;
				cur = null;
				final SegmentedChainBucketSegment oldSeg = seg;
				seg = null;
				try {
					dbJobRunner.runBlocking(new DBJob() {
						
						@Override
						public boolean run(ObjectContainer container, ClientContext context) {
							if(container.ext().isStored(oldSeg)) {
								if(!container.ext().isActive(oldSeg)) {
									Logger.error(this, "OLD SEGMENT STORED BUT NOT ACTIVE: "+oldSeg, new Exception("error"));
									container.activate(oldSeg, 1);
								}
							}
							oldSeg.storeTo(container);
							container.ext().store(segments, 1);
							container.ext().store(SegmentedBucketChainBucket.this, 1);
							container.deactivate(oldSeg, 1);
							// If there is only one segment, we didn't add a killMe.
							// Add one now.
							synchronized(SegmentedBucketChainBucket.this) {
								if(killMe != null) return true;
								killMe = new SegmentedBucketChainBucketKillJob(SegmentedBucketChainBucket.this);
							}
							try {
								killMe.scheduleRestart(container, context);
							} catch (DatabaseDisabledException e) {
								// Impossible.
							}
							return true;
						}
						
					}, NativeThread.HIGH_PRIORITY);
				} catch (DatabaseDisabledException e) {
					throw new IOException("Database disabled");
				}
			}
		};
	}

	private transient SegmentedBucketChainBucketKillJob killMe;
	
	private transient boolean runningSegStore;
	
	protected SegmentedChainBucketSegment makeSegment(int index, final SegmentedChainBucketSegment oldSeg) {
		if(logMINOR)
			Logger.minor(this, "Make a segment for "+this+" index "+index+ "old "+oldSeg);
		if(oldSeg != null) {
			synchronized(this) {
				while(runningSegStore) {
					Logger.normal(this, "Waiting for last segment-store job to finish on "+this);
					try {
						wait();
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				runningSegStore = true;
			}
			try {
			dbJobRunner.runBlocking(new DBJob() {
				
				@Override
				public boolean run(ObjectContainer container, ClientContext context) {
					try {
						oldSeg.storeTo(container);
						container.ext().store(segments, 1);
						container.ext().store(SegmentedBucketChainBucket.this, 1);
						container.deactivate(oldSeg, 1);
						synchronized(SegmentedBucketChainBucket.this) {
							if(killMe != null) return true;
							killMe = new SegmentedBucketChainBucketKillJob(SegmentedBucketChainBucket.this);
						}
						killMe.scheduleRestart(container, context);
					} catch (DatabaseDisabledException e) {
						// Impossible.
					} finally {
						synchronized(SegmentedBucketChainBucket.this) {
							runningSegStore = false;
							SegmentedBucketChainBucket.this.notifyAll();
						}
					}
					return true;
				}
				
			}, NativeThread.HIGH_PRIORITY-1);
			} catch (Throwable t) {
				Logger.error(this, "Caught throwable: "+t, t);
				runningSegStore = false;
			}
		}
		synchronized(this) {
			SegmentedChainBucketSegment seg = new SegmentedChainBucketSegment(this);
			if(segments.size() != index) throw new IllegalArgumentException("Asked to add segment "+index+" but segments length is "+segments.size());
			segments.add(seg);
			return seg;
		}
	}

	@Override
	public boolean isReadOnly() {
		return readOnly;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		// Valid no-op if we haven't been stored.
	}

	@Override
	public void setReadOnly() {
		readOnly = true;
	}

	@Override
	public synchronized long size() {
		return size;
	}

	/**
	 * Note that we don't recurse inside the segments, as it would produce a huge
	 * transaction. So you will need to close the OutputStream to commit the 
	 * progress of writing to a file. And yes, we can't append. So you need to
	 * write everything before storing the bucket.
	 * 
	 * FIXME: Enforce the rule that you must close any OutputStream's before 
	 * calling storeTo().
	 */
	@Override
	public void storeTo(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}
	
	public Bucket[] getBuckets() {
		final BucketArrayWrapper baw = new BucketArrayWrapper();
		try {
			dbJobRunner.runBlocking(new DBJob() {

				@Override
				public boolean run(ObjectContainer container, ClientContext context) {
					baw.buckets = getBuckets(container);
					return false;
				}
				
			}, NativeThread.HIGH_PRIORITY);
		} catch (DatabaseDisabledException e) {
			// Impossible.
			return null;
		}
		return baw.buckets;
	}

	protected synchronized Bucket[] getBuckets(ObjectContainer container) {
		int segs = segments.size();
		if(segs == 0) return new Bucket[0];
		SegmentedChainBucketSegment seg = segments.get(segs-1);
		container.activate(seg, 1);
		seg.activateBuckets(container);
		int size = (segs - 1) * segmentSize + seg.size();
		Bucket[] buckets = new Bucket[size];
		seg.shallowCopyBuckets(buckets, (segs-1)*segmentSize);
		container.deactivate(seg, 1);
		int pos = 0;
		for(int i=0;i<(segs-1);i++) {
			seg = segments.get(i);
			container.activate(seg, 1);
			seg.activateBuckets(container);
			seg.shallowCopyBuckets(buckets, pos);
			container.deactivate(seg, 1);
			pos += segmentSize;
		}
		return buckets;
	}
	
	
	private boolean clearing;
	
	public synchronized void clear() {
		// Due to memory issues, we cannot complete this before we return
		synchronized(this) {
			clearing = true;
		}
		DBJob clearJob = new DBJob() {
			
			@Override
			public boolean run(ObjectContainer container, ClientContext context) {
				if(!container.ext().isStored(SegmentedBucketChainBucket.this)) {
					Logger.error(this, "Bucket not stored in clearJob, already deleted???");
					container.delete(this);
					return false;
				}
				SegmentedChainBucketSegment segment = null;
				synchronized(this) {
					if(!segments.isEmpty())
						segment = segments.remove(0);
				}
				if(segment != null) {
					container.activate(segment, 1);
					if(logMINOR)
						Logger.minor(SegmentedBucketChainBucket.this, "Clearing segment "+segment);
					segment.clear(container);
					synchronized(this) {
						if(!segments.isEmpty()) {
							try {
								dbJobRunner.queue(this, NativeThread.HIGH_PRIORITY-1, true);
								dbJobRunner.queueRestartJob(this, NativeThread.HIGH_PRIORITY-1, container, false);
							} catch (DatabaseDisabledException e) {
								// Impossible.
							}
							container.store(segments);
							container.store(SegmentedBucketChainBucket.this);
							return true;
						}
					}
				}
				container.delete(segments);
				container.delete(SegmentedBucketChainBucket.this);
				container.delete(this);
				synchronized(SegmentedBucketChainBucket.this) {
					if(killMe == null) return true;
				}
				try {
					dbJobRunner.removeRestartJob(killMe, NativeThread.HIGH_PRIORITY, container);
				} catch (DatabaseDisabledException e) {
					// Impossible.
					// It will be re-run, no big deal.
				}
				container.delete(killMe);
				return true;
			}

		};
		// Must be run blocking so that if we are on the database thread, the job is
		// added before committing. If we are not on the database thread, it doesn't
		// matter.
		try {
			dbJobRunner.runBlocking(clearJob, NativeThread.HIGH_PRIORITY-1);
		} catch (DatabaseDisabledException e) {
			Logger.error(this, "Unable to clear() on "+this+" because database is disabled");
		}
	}

	/**
	 * @param container
	 * @return True if there is more work to do. We don't want to do everything in one transaction because
	 * this bucket could be enormous.
	 */
	synchronized boolean removeContents(ObjectContainer container) {
		while(segments.size() > 0) {
			Logger.normal(this, "Freeing unfinished unstored bucket "+this+" segments left "+segments.size());
			// Remove the first so the space is reused at the beginning not at the end.
			// Removing from the end results in not shrinking.
			SegmentedChainBucketSegment seg = segments.remove(0);
			if(seg == null) {
				// Already removed.
				continue;
			}
			container.activate(seg, 1);
			if(logMINOR) Logger.minor(this, "Removing segment "+seg+" size "+seg.size());
			if(clearing) {
				seg.clear(container);
			} else {
				seg.activateBuckets(container);
				seg.free();
				seg.removeFrom(container);
			}
			if(segments.size() > 0) {
				container.store(segments);
				container.store(this);
				return true; // Do some more in the next transaction
			} else break;
		}
		if(logMINOR) Logger.minor(this, "Removed segments for "+this);
		container.delete(segments);
		container.delete(this);
		if(logMINOR) Logger.minor(this, "Removed "+this);
		freed = true; // Just in case it wasn't already.
		return false;
	}
	
	// No objectCan* callbacks, because we need to be able to store it in the database due to the KillJob needing it.
}
