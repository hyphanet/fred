/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

public class BucketChainBucket implements Bucket {
	
	private final Vector<Bucket> buckets;
	public final long bucketSize;
	private long size;
	private boolean freed;
	private boolean readOnly;
	private final BucketFactory bf;
	private final boolean cacheWholeBucket;

	/**
	 * @param bucketSize
	 * @param bf
	 * @param dbJobRunner If not null, use this to store buckets to disk progressively
	 * to avoid a big transaction at the end. Caller then MUST call storeTo() at some point.
	 */
	public BucketChainBucket(long bucketSize, BucketFactory bf, boolean cacheWholeBucket) {
		this.bucketSize = bucketSize;
		this.buckets = new Vector<Bucket>();
		this.bf = bf;
		size = 0;
		freed = false;
		readOnly = false;
		this.cacheWholeBucket = cacheWholeBucket;
	}

	private BucketChainBucket(Vector<Bucket> newBuckets, long bucketSize2, long size2, boolean readOnly, BucketFactory bf2, boolean cacheWholeBucket) {
		this.buckets = newBuckets;
		this.bucketSize = bucketSize2;
		this.size = size2;
		this.readOnly = readOnly;
		this.bf = bf2;
		this.cacheWholeBucket = cacheWholeBucket;
	}

	@Override
	public void free() {
		Bucket[] list;
		synchronized(this) {
			list = getBuckets();
			freed = true;
			buckets.clear();
		}
		for(Bucket l: list) {
			l.free();
		}
	}
	
	/** Equivalent to free(), but don't free the underlying buckets. */
	void clear() {
		synchronized(this) {
			size = 0;
			buckets.clear();
		}
	}

	public synchronized Bucket[] getBuckets() {
		return buckets.toArray(new Bucket[buckets.size()]);
	}

	@Override
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
					synchronized(BucketChainBucket.this) {
						// No more data to read at the moment.
						if(readBytes >= size) return -1;
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
					synchronized(BucketChainBucket.this) {
						// No more data to read at the moment.
						if(readBytes >= size) return -1;
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

	@Override
	public String getName() {
		return "BucketChainBucket";
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		Bucket[] list;
		synchronized(this) {
			if(readOnly) throw new IOException("Read-only");
			if(freed) throw new IOException("Freed");
			size = 0;
			list = getBuckets();
			buckets.clear();
		}
		for(Bucket l: list) {
			l.free();
		}
		return new OutputStream() {

			private int bucketNo = 0;
			private OutputStream curBucketStream;
			private long bucketLength = 0;
			private ByteArrayOutputStream baos;
			{
				if(cacheWholeBucket)
					baos = new ByteArrayOutputStream((int)bucketSize);
				else
					curBucketStream = makeBucketOutputStream(0);
			}
			
			@Override
			public void write(int c) throws IOException {
				synchronized(BucketChainBucket.this) {
					if(freed) {
						if(baos == null) {
							curBucketStream.close();
							curBucketStream = null;
						}
						throw new IOException("Freed");
					}
					if(readOnly) {
						if(baos == null) {
							curBucketStream.close();
							curBucketStream = null;
						}
						throw new IOException("Read-only");
					}
				}
				if(bucketLength == bucketSize) {
					if(baos != null) {
						OutputStream os = makeBucketOutputStream(bucketNo);
						bucketNo++;
						try {
						os.write(baos.toByteArray());
						baos.reset();
						} finally {
						os.close();
						}
					} else {
						curBucketStream.close();
						curBucketStream = makeBucketOutputStream(++bucketNo);
					}
					
					bucketLength = 0;
				}
				if(baos != null)
					baos.write(c);
				else
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
						if(baos == null) {
							curBucketStream.close();
							curBucketStream = null;
						}
						throw new IOException("Freed");
					}
					if(readOnly) {
						if(baos == null) {
							curBucketStream.close();
							curBucketStream = null;
						}
						throw new IOException("Read-only");
					}
				}
				if(length <= 0) return;
				if(bucketLength == bucketSize) {
					if(baos != null) {
						OutputStream os = makeBucketOutputStream(bucketNo);
						bucketNo++;
						try {
						os.write(baos.toByteArray());
						baos.reset();
						} finally {
						os.close();
						}
					} else {
						curBucketStream.close();
						curBucketStream = makeBucketOutputStream(++bucketNo);
					}
					
					bucketLength = 0;
				}
				if(bucketLength + length > bucketSize) {
					int split = (int) (bucketSize - bucketLength);
					write(buf, offset, split);
					write(buf, offset + split, length - split);
					return;
				}
				if(baos != null)
					baos.write(buf, offset, length);
				else
					curBucketStream.write(buf, offset, length);
				bucketLength += length;
				synchronized(BucketChainBucket.this) {
					size += length;
				}
			}
			
			@Override
			public void close() throws IOException {
				if(baos != null && baos.size() > 0) {
					OutputStream os = makeBucketOutputStream(bucketNo);
					bucketNo++;
					try {
					os.write(baos.toByteArray());
					baos.reset();
					} finally {
					os.close();
					}
				} else if(curBucketStream != null) {
					curBucketStream.close();
				}
			}
			
		};
	}

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
		return bucket.getOutputStream();
	}
	
	@Override
	public boolean isReadOnly() {
		return readOnly;
	}

	@Override
	public void setReadOnly() {
		readOnly = true;
	}

	@Override
	public long size() {
		return size;
	}

	@Override
	public void storeTo(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bucket createShadow() {
		Vector<Bucket> newBuckets = new Vector<Bucket>();
		for(int i=0;i<buckets.size();i++) {
			Bucket data = buckets.get(i);
			Bucket shadow = data.createShadow();
			if(shadow == null) {
				for(Bucket bucket : newBuckets)
					bucket.free();
				return null;
			}
			newBuckets.add(shadow);
		}
		return new BucketChainBucket(newBuckets, bucketSize, size, true, bf, cacheWholeBucket);
	}

}
