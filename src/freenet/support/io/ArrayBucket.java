package freenet.support.io;

import freenet.support.Logger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;

import freenet.support.api.Bucket;

/**
 * A bucket that stores data in RAM
 */
public class ArrayBucket implements Bucket {

	private final ArrayList<byte[]> data;
	private final String name;
	private volatile boolean readOnly;
	
	/** The maximum size of the bucket; -1 means no maxSize */
	private final long maxSize;
	private long size;
	
	private static boolean logDEBUG = Logger.shouldLog(Logger.DEBUG, ArrayBucket.class);
	
	public ArrayBucket(long maxSize) {
		this("ArrayBucket", maxSize);
	}
	
	public ArrayBucket(byte[] finalData) {
		this(finalData, finalData.length);
		setReadOnly();
	}

	public ArrayBucket(byte[] initdata, long maxSize) {
		this("ArrayBucket", maxSize);
		data.add(initdata);
	}

	ArrayBucket(String name, long maxSize) {
		data = new ArrayList<byte[]>();
		this.name = name;
		this.maxSize = maxSize;
		if(logDEBUG && maxSize < 0)
			Logger.minor(this, "Has been called with maxSize<0 !", new NullPointerException());
	}

	public synchronized OutputStream getOutputStream() throws IOException {
		if(readOnly) throw new IOException("Read only");
		return new ArrayBucketOutputStream();
	}

	public synchronized InputStream getInputStream() {
		return new ArrayBucketInputStream();
	}

	@Override
	public synchronized String toString() {
		StringBuffer s = new StringBuffer(250);
		for (Iterator i = data.iterator(); i.hasNext();) {
			byte[] b = (byte[]) i.next();
			s.append(new String(b));
		}
		return s.toString();
	}

	public synchronized void read(InputStream in) throws IOException {
		OutputStream out = new ArrayBucketOutputStream();
		int i;
		byte[] b = new byte[8 * 1024];
		while ((i = in.read(b)) != -1) {
			out.write(b, 0, i);
		}
		out.close();
	}

	public synchronized long size() {
		return size;
	}

	public String getName() {
		return name;
	}

	private class ArrayBucketOutputStream extends ByteArrayOutputStream {
		boolean hasBeenClosed = false;
		
		public ArrayBucketOutputStream() {
			super();
		}
		
		@Override
		public synchronized void write(byte b[], int off, int len) {
			if(readOnly) throw new IllegalStateException("Read only");
			long sizeIfWritten = size + len;
			if(maxSize > -1 && maxSize < sizeIfWritten) // FIXME: should be IOE but how to do it?
				throw new IllegalArgumentException("The maxSize of the bucket is "+maxSize+
					" and writing "+len+ " bytes to it would make it oversize!");
			super.write(b, off, len);
			size = sizeIfWritten;
		}
		
		@Override
		public synchronized void write(int b) {
			if(readOnly) throw new IllegalStateException("Read only");
			long sizeIfWritten = size + 1;
			if(maxSize > -1 && maxSize < sizeIfWritten) // FIXME: should be IOE but how to do it?
				throw new IllegalArgumentException("The maxSize of the bucket is "+maxSize+
					" and writing 1 byte to it would make it oversize!");
			super.write(b);
			size = sizeIfWritten;
		}

		@Override
		public synchronized void close() throws IOException {
			if(hasBeenClosed) return;
			hasBeenClosed = true;
			data.add(super.toByteArray());
			if(readOnly) throw new IOException("Read only");
		}
	}

	private class ArrayBucketInputStream extends InputStream {
		
		private Iterator i;
		private ByteArrayInputStream in;

		public ArrayBucketInputStream() {
			i = data.iterator();
		}

		public synchronized int read() {
			return priv_read();
		}

		private synchronized int priv_read() {
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream((byte[]) i.next());
				} else {
					return -1;
				}
			}
			int x = in.read();
			if (x == -1) {
				in = null;
				return priv_read();
			} else {
				return x;
			}
		}

		@Override
		public synchronized int read(byte[] b) {
			return priv_read(b, 0, b.length);
		}

		@Override
		public synchronized int read(byte[] b, int off, int len) {
			return priv_read(b, off, len);
		}

		private synchronized int priv_read(byte[] b, int off, int len) {
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream((byte[]) i.next());
				} else {
					return -1;
				}
			}
			int x = in.read(b, off, len);
			if (x == -1) {
				in = null;
				return priv_read(b, off, len);
			} else {
				return x;
			}
		}

		@Override
		public synchronized int available() {
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream((byte[]) i.next());
				} else {
					return 0;
				}
			}
			return in.available();
		}

	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly() {
		readOnly = true;
	}

	public synchronized void free() {
		readOnly = true;
		data.clear();
		size = 0;
		// Not much else we can do.
	}

	public synchronized byte[] toByteArray() {
		long sz = size();
		int bufSize = (int)sz;
		byte[] buf = new byte[bufSize];
		int index = 0;
		for(Iterator i=data.iterator();i.hasNext();) {
			byte[] obuf = (byte[]) i.next();
			System.arraycopy(obuf, 0, buf, index, obuf.length);
			index += obuf.length;
		}
		if(index != buf.length)
			throw new IllegalStateException();
		return buf;
	}
}
