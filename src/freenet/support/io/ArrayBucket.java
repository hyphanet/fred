package freenet.support.io;

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
	
	private long size;
	
	/** Create a new immutable ArrayBucket with the data provided and a name*/
	public ArrayBucket(String name, byte[] initdata) {
		this(name);
		data.add(initdata);
		this.size = initdata.length;
		setReadOnly();
	}
	
	/** Create a new immutable ArrayBucket with the data provided */	
	public ArrayBucket(byte[] initdata) {
		this();
		data.add(initdata);
		this.size = initdata.length;
		setReadOnly();
	}

	/** Create a new array bucket */
	public ArrayBucket() {
		this("ArrayBucket");
	}

	/** Create a new array bucket with the provided name */
	public ArrayBucket(String name) {
		data = new ArrayList<byte[]>();
		this.name = name;
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
		private boolean hasBeenClosed = false;
		
		public ArrayBucketOutputStream() {
			super();
		}
		
		@Override
		public synchronized void write(byte b[], int off, int len) {
			if(readOnly) throw new IllegalStateException("Read only");
			if(hasBeenClosed) throw new IllegalStateException("Has been closed!");
			super.write(b, off, len);
			size +=len;
		}
		
		@Override
		public synchronized void write(int b) {
			if(readOnly) throw new IllegalStateException("Read only");
			if(hasBeenClosed) throw new IllegalStateException("Has been closed!");
			super.write(b);
			size++;
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
		
		private final Iterator<byte[]> i;
		private ByteArrayInputStream in;
		private boolean hasBeenClosed = false;

		public ArrayBucketInputStream() {
			i = data.iterator();
		}

		public synchronized int read() throws IOException {
			return priv_read();
		}

		private synchronized int priv_read() throws IOException {
			if(hasBeenClosed) throw new IOException("Has been closed!");
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream(i.next());
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
		public synchronized int read(byte[] b) throws IOException {
			return priv_read(b, 0, b.length);
		}

		@Override
		public synchronized int read(byte[] b, int off, int len) throws IOException {
			return priv_read(b, off, len);
		}

		private synchronized int priv_read(byte[] b, int off, int len) throws IOException {
			if(hasBeenClosed) throw new IOException("Has been closed!");
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream(i.next());
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
		public synchronized int available() throws IOException {
			if(hasBeenClosed) throw new IOException("Has been closed!");
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream(i.next());
				} else {
					return 0;
				}
			}
			return in.available();
		}

		@Override
		public synchronized void close() throws IOException {
			if(hasBeenClosed) return;
			hasBeenClosed = true;
			Closer.close(in);
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
		// Not much else we can do.
	}

	public synchronized byte[] toByteArray() {
		long sz = size();
		int bufSize = (int)sz;
		byte[] buf = new byte[bufSize];
		int index = 0;
		for(byte[] obuf : data) {
			System.arraycopy(obuf, 0, buf, index, obuf.length);
			index += obuf.length;
		}
		if(index != buf.length)
			throw new IllegalStateException();
		return buf;
	}
}
