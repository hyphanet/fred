/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

public class BucketChainBucket implements Bucket {
	
	private final Vector<Bucket> buckets;
	public final long bucketSize;
	private long size;
	private boolean freed;
	private boolean readOnly;
	private final BucketFactory bf;
	
	public BucketChainBucket(long bucketSize, BucketFactory bf) {
		this.bucketSize = bucketSize;
		this.buckets = new Vector<Bucket>();
		this.bf = bf;
		size = 0;
		freed = false;
		readOnly = false;
	}

	public void free() {
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
		}
		return new InputStream() {

			private int bucketNo = 0;
			private InputStream curBucketStream = getBucketInputStream(0);
			private long readBytes;
			
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
			
			public void close() throws IOException {
				if(curBucketStream != null)
					curBucketStream.close();
			}
			
		};
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
			
			public void write(byte[] buf) throws IOException {
				write(buf, 0, buf.length);
			}
			
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
			
			public void close() throws IOException {
				if(curBucketStream != null)
					curBucketStream.close();
			}
			
		};
	}

	protected OutputStream makeBucketOutputStream(int i) throws IOException {
		Bucket bucket = bf.makeBucket(bucketSize);
		buckets.add(bucket);
		if (buckets.size() != i + 1)
			throw new IllegalStateException("Added bucket, size should be " + (i + 1) + " but is " + buckets.size());
		if (buckets.get(i) != bucket)
			throw new IllegalStateException("Bucket got replaced. Race condition?");
		return bucket.getOutputStream();
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly() {
		readOnly = true;
	}

	public long size() {
		return size;
	}

}
