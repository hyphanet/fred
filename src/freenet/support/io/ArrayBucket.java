package freenet.support.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.db4o.ObjectContainer;

import freenet.support.api.Bucket;

/**
 * A bucket that stores data in the memory.
 * 
 * FIXME: No synchronization, should there be?
 * 
 * @author oskar
 */
public class ArrayBucket implements Bucket {
	private volatile byte[] data;
	private String name;
	private boolean readOnly;

	public ArrayBucket() {
		this("ArrayBucket");
	}

	public ArrayBucket(byte[] initdata) {
		this("ArrayBucket");
		data = initdata;
	}

	public ArrayBucket(String name) {
		data = new byte[0];
		this.name = name;
	}

	public OutputStream getOutputStream() throws IOException {
		if(readOnly) throw new IOException("Read only");
		return new ArrayBucketOutputStream();
	}

	public InputStream getInputStream() {
		return new ByteArrayInputStream(data);
	}

	@Override
	public String toString() {
		return new String(data);
	}

	public long size() {
		return data.length;
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
			data = super.toByteArray();
			if(readOnly) throw new IOException("Read only");
			// FIXME maybe we should throw on write instead? :)
			hasBeenClosed = true;
		}
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly() {
		readOnly = true;
	}

	public void free() {
		data = new byte[0];
		// Not much else we can do.
	}

	public byte[] toByteArray() {
		long sz = size();
		int size = (int)sz;
		byte[] buf = new byte[size];
		System.arraycopy(data, 0, buf, 0, size);
		return buf;
	}

	public void storeTo(ObjectContainer container) {
		container.store(data);
		container.store(this);
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(data);
		container.delete(this);
	}

	public Bucket createShadow() {
		return null;
	}
}
