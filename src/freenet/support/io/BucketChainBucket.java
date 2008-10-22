/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

public class BucketChainBucket implements Bucket {
	
	private final Vector<Bucket> buckets;
	public final long bucketSize;
	private long size;
	private boolean freed;
	private boolean readOnly;
	private final BucketFactory bf;
	private transient DBJobRunner dbJobRunner;
	boolean stored;

	/**
	 * @param bucketSize
	 * @param bf
	 * @param dbJobRunner If not null, use this to store buckets to disk progressively
	 * to avoid a big transaction at the end. Caller then MUST call storeTo() at some point.
	 */
	public BucketChainBucket(long bucketSize, BucketFactory bf, DBJobRunner dbJobRunner) {
		this.bucketSize = bucketSize;
		this.buckets = new Vector<Bucket>();
		this.bf = bf;
		this.dbJobRunner = dbJobRunner;
		size = 0;
		freed = false;
		readOnly = false;
	}

	private BucketChainBucket(Vector newBuckets, long bucketSize2, long size2, boolean readOnly, BucketFactory bf2) {
		this.buckets = newBuckets;
		this.bucketSize = bucketSize2;
		this.size = size2;
		this.readOnly = readOnly;
		this.bf = bf2;
		dbJobRunner = null;
	}

	public void free() {
		if(dbJobRunner != null) {
			dbJobRunner.runBlocking(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					removeFrom(container);
				}
				
			}, NativeThread.HIGH_PRIORITY);
		}
		Bucket[] list;
		synchronized(this) {
			list = getBuckets();
			freed = true;
			buckets.clear();
		}
		for(int i=0;i<list.length;i++) {
			list[i].free();
		}
	}
	
	/** Equivalent to free(), but don't free the underlying buckets. */
	public void clear() {
		synchronized(this) {
			size = 0;
			buckets.clear();
		}
	}

	public synchronized Bucket[] getBuckets() {
		return buckets.toArray(new Bucket[buckets.size()]);
	}

	public InputStream getInputStream() throws IOException {
		synchronized(this) {
			if(freed) throw new IOException("Freed");
			if(buckets.size() == 0) return new NullInputStream();
		return new InputStream() {

			private int bucketNo = 0;
			private InputStream curBucketStream = getBucketInputStream(0);
			private long readBytes;
			
			@Override
			public int read() throws IOException {
				synchronized(BucketChainBucket.this) {
					if(freed) {
						curBucketStream.close();
						curBucketStream = null;
						throw new IOException("Freed");
					}
				}
				while(true) {
					if(curBucketStream == null) {
						curBucketStream = getBucketInputStream(0);
						if(curBucketStream == null) {
							return -1;
						}
					}
					try {
						int x = curBucketStream.read();
						if(x >= 0) {
							readBytes++;
							return x;
						}
					} catch (EOFException e) {
						// Handle the same
					}
					bucketNo++;
					curBucketStream.close();
					curBucketStream = getBucketInputStream(bucketNo++);
				}
			}
			
			@Override
			public int read(byte[] buf) throws IOException {
				synchronized(BucketChainBucket.this) {
					if(freed) {
						curBucketStream.close();
						curBucketStream = null;
						throw new IOException("Freed");
					}
				}
				return read(buf, 0, buf.length);
			}
			
			@Override
			public int read(byte[] buf, int offset, int length) throws IOException {
				synchronized(BucketChainBucket.this) {
					if(freed) {
						curBucketStream.close();
						curBucketStream = null;
						throw new IOException("Freed");
					}
				}
				if(length == 0) return 0;
				while(true) {
					if(curBucketStream == null) {
						curBucketStream = getBucketInputStream(0);
						if(curBucketStream == null) {
							return -1;
						}
					}
					try {
						int x = curBucketStream.read(buf, offset, length);
						if(x > 0) {
							readBytes += x;
							return x;
						}
					} catch (EOFException e) {
						// Handle the same
					}
					bucketNo++;
					curBucketStream.close();
					curBucketStream = getBucketInputStream(bucketNo++);
				}
			}
			
			@Override
			public int available() throws IOException {
				synchronized(BucketChainBucket.this) {
					if(freed) {
						curBucketStream.close();
						curBucketStream = null;
						throw new IOException("Freed");
					}
				}
				return (int) Math.min(Integer.MAX_VALUE, size() - readBytes);
			}
			
			@Override
			public void close() throws IOException {
				if(curBucketStream != null)
					curBucketStream.close();
			}
			
		};
		}
	}

	protected synchronized InputStream getBucketInputStream(int i) throws IOException {
		Bucket bucket = buckets.get(i);
		if(bucket == null) return null;
		return bucket.getInputStream();
	}

	public String getName() {
		return "BucketChainBucket";
	}

	public OutputStream getOutputStream() throws IOException {
		Bucket[] list;
		synchronized(this) {
			if(readOnly) throw new IOException("Read-only");
			if(freed) throw new IOException("Freed");
			size = 0;
			list = getBuckets();
			buckets.clear();
		}
		for(int i=0;i<list.length;i++) {
			list[i].free();
		}
		return new OutputStream() {

			private int bucketNo = 0;
			private OutputStream curBucketStream = makeBucketOutputStream(0);
			private long bucketLength = 0;
			
			@Override
			public void write(int c) throws IOException {
				synchronized(BucketChainBucket.this) {
					if(freed) {
						curBucketStream.close();
						curBucketStream = null;
						throw new IOException("Freed");
					}
					if(readOnly) {
						curBucketStream.close();
						curBucketStream = null;
						throw new IOException("Read-only");
					}
				}
				if(bucketLength == bucketSize) {
					curBucketStream.close();
					curBucketStream = makeBucketOutputStream(++bucketNo);
					bucketLength = 0;
				}
				curBucketStream.write(c);
				bucketLength++;
				synchronized(BucketChainBucket.this) {
					size++;
				}
			}
			
			@Override
			public void write(byte[] buf) throws IOException {
				write(buf, 0, buf.length);
			}
			
			@Override
			public void write(byte[] buf, int offset, int length) throws IOException {
				synchronized(BucketChainBucket.this) {
					if(freed) {
						curBucketStream.close();
						curBucketStream = null;
						throw new IOException("Freed");
					}
					if(readOnly) {
						curBucketStream.close();
						curBucketStream = null;
						throw new IOException("Read-only");
					}
				}
				if(length <= 0) return;
				if(bucketLength == bucketSize) {
					curBucketStream.close();
					curBucketStream = makeBucketOutputStream(++bucketNo);
					bucketLength = 0;
				}
				if(bucketLength + length > bucketSize) {
					int split = (int) (bucketSize - bucketLength);
					write(buf, offset, split);
					write(buf, offset + split, length - split);
					return;
				}
				curBucketStream.write(buf, offset, length);
				bucketLength += length;
				synchronized(BucketChainBucket.this) {
					size += length;
				}
			}
			
			@Override
			public void close() throws IOException {
				if(curBucketStream != null)
					curBucketStream.close();
			}
			
		};
	}

	private final DBJob killMe = new BucketChainBucketKillJob(this);
	
	protected OutputStream makeBucketOutputStream(int i) throws IOException {
		Bucket bucket;
		synchronized(this) {
			bucket = bf.makeBucket(bucketSize);
			buckets.add(bucket);
			if (buckets.size() != i + 1)
				throw new IllegalStateException("Added bucket, size should be " + (i + 1) + " but is " + buckets.size());
			if (buckets.get(i) != bucket)
				throw new IllegalStateException("Bucket got replaced. Race condition?");
		}
		if(dbJobRunner != null && !stored && buckets.size() % 1024 == 0) {
			dbJobRunner.runBlocking(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					synchronized(BucketChainBucket.this) {
						for(int i=storedTo;i<buckets.size();i++) {
							buckets.get(i).storeTo(container);
						}
						storedTo = buckets.size() - 1; // include the last one next time
						container.store(buckets);
					}
					boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
					if(logMINOR)
						Logger.minor(this, "Storing "+BucketChainBucket.this);
					container.store(BucketChainBucket.this);
					if(logMINOR)
						Logger.minor(this, "Queueing restart job for "+BucketChainBucket.this);
					dbJobRunner.queueRestartJob(killMe, NativeThread.HIGH_PRIORITY, container);
				}
				
			}, NativeThread.HIGH_PRIORITY);
		}
		return bucket.getOutputStream();
	}

	private int storedTo = 0;
	
	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly() {
		readOnly = true;
	}

	public long size() {
		return size;
	}

	public void storeTo(ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Storing to database: "+this);
		for(int i=0;i<buckets.size();i++)
			((Bucket) buckets.get(i)).storeTo(container);
		stored = true;
		container.store(buckets);
		container.store(this);
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Removing restart job: "+this);
		dbJobRunner.removeRestartJob(killMe, NativeThread.HIGH_PRIORITY, container);
	}

	public void removeFrom(ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Removing from database: "+this);
		Bucket[] list;
		synchronized(this) {
			list = (Bucket[]) buckets.toArray(new Bucket[buckets.size()]);
			buckets.clear();
		}
		for(int i=0;i<list.length;i++)
			list[i].removeFrom(container);
		container.delete(buckets);
		container.delete(this);
		stored = false;
		dbJobRunner.removeRestartJob(killMe, NativeThread.HIGH_PRIORITY, container);
	}

	public Bucket createShadow() throws IOException {
		Vector newBuckets = new Vector();
		for(int i=0;i<buckets.size();i++) {
			Bucket data = (Bucket) buckets.get(i);
			Bucket shadow = data.createShadow();
			if(shadow == null) {
				// Shadow buckets don't need to be freed.
				return null;
			}
			newBuckets.add(shadow);
		}
		return new BucketChainBucket(newBuckets, bucketSize, size, true, bf);
	}

	// For debugging
	
	public boolean objectCanUpdate(ObjectContainer container) {
		return true;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		return true;
	}
	
}
