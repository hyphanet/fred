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
 * @author oskar
 */
public class ArrayBucket implements Bucket {

	private final ArrayList data;
	private final String name;
	private volatile boolean readOnly;
	private OutputStream os = null;
	private InputStream is = null;

	public ArrayBucket() {
		this("ArrayBucket");
	}

	public ArrayBucket(byte[] initdata) {
		this("ArrayBucket");
		data.add(initdata);
	}

	ArrayBucket(String name) {
		data = new ArrayList();
		this.name = name;
	}

	public synchronized OutputStream getOutputStream() throws IOException {
		if(readOnly) throw new IOException("Read only");
		if(os == null)
			os = new ArrayBucketOutputStream();
		return os;
	}

	public synchronized InputStream getInputStream() {
		if(is == null)
			is = new ArrayBucketInputStream();
		return is;
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
		long size = 0;
		for (Iterator i = data.iterator(); i.hasNext();) {
			byte[] b = (byte[]) i.next();
			size += b.length;
		}
		return size;
	}

	public String getName() {
		return name;
	}

	private class ArrayBucketOutputStream extends ByteArrayOutputStream {
		
		public ArrayBucketOutputStream() {
			super();
		}
		
		@Override
		public synchronized void write(byte b[], int off, int len) {
			if(readOnly) throw new IllegalStateException("Read only");
			super.write(b, off, len);
		}
		
		@Override
		public synchronized void write(int b) {
			if(readOnly) throw new IllegalStateException("Read only");
			super.write(b);
		}

		@Override
		public synchronized void close() throws IOException {
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
			int i = in.read();
			if (i == -1) {
				in = null;
				return priv_read();
			} else {
				return i;
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
			int i = in.read(b, off, len);
			if (i == -1) {
				in = null;
				return priv_read(b, off, len);
			} else {
				return i;
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
		data.clear();
		// Not much else we can do.
	}

	public synchronized byte[] toByteArray() {
		long sz = size();
		int size = (int)sz;
		byte[] buf = new byte[size];
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
