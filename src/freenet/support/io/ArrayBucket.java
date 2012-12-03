package freenet.support.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

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

	@Override
	public OutputStream getOutputStream() throws IOException {
		if(readOnly) throw new IOException("Read only");
		return new ArrayBucketOutputStream();
	}

	@Override
	public InputStream getInputStream() {
		return new ByteArrayInputStream(data);
	}

	@Override
	public String toString() {
		return new String(data);
	}

	@Override
	public long size() {
		return data.length;
	}

	@Override
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

	@Override
	public boolean isReadOnly() {
		return readOnly;
	}

	@Override
	public void setReadOnly() {
		readOnly = true;
	}

	@Override
	public void free() {
		data = new byte[0];
		// Not much else we can do.
	}

	public byte[] toByteArray() {
		long sz = size();
		int size = (int)sz;
		return Arrays.copyOf(data, size);
	}

	@Override
	public void storeTo(ObjectContainer container) {
		container.store(data);
		container.store(this);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(data);
		container.delete(this);
	}

	@Override
	public Bucket createShadow() {
		return null;
	}
}
