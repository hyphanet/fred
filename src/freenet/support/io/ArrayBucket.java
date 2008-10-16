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
 * A bucket that stores data in the memory.
 * 
 * FIXME: No synchronization, should there be?
 * 
 * @author oskar
 */
public class ArrayBucket implements Bucket {

	private final ArrayList<byte[]> data;
	private String name;
	private boolean readOnly;

	public ArrayBucket() {
		this("ArrayBucket");
	}

	public ArrayBucket(byte[] initdata) {
		this("ArrayBucket");
		data.add(initdata);
	}

	public ArrayBucket(String name) {
		data = new ArrayList<byte[]>();
		this.name = name;
	}

	public OutputStream getOutputStream() throws IOException {
		if(readOnly) throw new IOException("Read only");
		return new ArrayBucketOutputStream();
	}

	public InputStream getInputStream() {
		return new ArrayBucketInputStream();
	}

	@Override
	public String toString() {
		StringBuilder s = new StringBuilder(250);
		for (byte[] b : data) {
			s.append(new String(b));
		}
		return s.toString();
	}

	public long size() {
		long size = 0;
		for (byte[] b : data) {
			size += b.length;
		}
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
		public synchronized void close() throws IOException {
			if(hasBeenClosed) return;
			data.add(super.toByteArray());
			if(readOnly) throw new IOException("Read only");
			// FIXME maybe we should throw on write instead? :)
			hasBeenClosed = true;
		}
	}

	private class ArrayBucketInputStream extends InputStream {
		
		private Iterator<byte[]> i;
		private ByteArrayInputStream in;

		public ArrayBucketInputStream() {
			i = data.iterator();
		}

		@Override
		public int read() {
			return priv_read();
		}

		private int priv_read() {
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream(i.next());
				} else {
					return -1;
				}
			}
			int i = in.read();
			if (i == -1) {
				in = null;
				return priv_read();
			} else {
				return i;
			}
		}

		@Override
		public int read(byte[] b) {
			return priv_read(b, 0, b.length);
		}

		@Override
		public int read(byte[] b, int off, int len) {
			return priv_read(b, off, len);
		}

		private int priv_read(byte[] b, int off, int len) {
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream(i.next());
				} else {
					return -1;
				}
			}
			int i = in.read(b, off, len);
			if (i == -1) {
				in = null;
				return priv_read(b, off, len);
			} else {
				return i;
			}
		}

		@Override
		public int available() {
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream(i.next());
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

	public void free() {
		data.clear();
		// Not much else we can do.
	}

	public byte[] toByteArray() {
		long sz = size();
		int size = (int)sz;
		byte[] buf = new byte[size];
		int index = 0;
		for (byte[] obuf : data) {			
			System.arraycopy(obuf, 0, buf, index, obuf.length);
			index += obuf.length;
		}
		if(index != buf.length)
			throw new IllegalStateException();
		return buf;
	}
}
