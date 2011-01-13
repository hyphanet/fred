package freenet.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.db4o.ObjectContainer;

import freenet.support.api.Bucket;

/**
 * Simple read-only array bucket. Just an adapter class to save some RAM.
 * Wraps a byte[], offset, length into a Bucket. Read-only. ArrayBucket on
 * the other hand is a chain of byte[]'s.
 */
public class SimpleReadOnlyArrayBucket implements Bucket {

	final byte[] buf;
	final int offset;
	final int length;
	
	public SimpleReadOnlyArrayBucket(byte[] buf, int offset, int length) {
		this.buf = buf;
		this.offset = offset;
		this.length = length;
	}
	
	public SimpleReadOnlyArrayBucket(byte[] buf) {
		this(buf, 0, buf.length);
	}
	
	public OutputStream getOutputStream() throws IOException {
		throw new IOException("Read only");
	}

	public InputStream getInputStream() throws IOException {
		return new ByteArrayInputStream(buf, offset, length);
	}

	public String getName() {
		return "SimpleReadOnlyArrayBucket: len="+length+ ' ' +super.toString();
	}

	public long size() {
		return length;
	}

	public boolean isReadOnly() {
		return true;
	}

	public void setReadOnly() {
		// Already read-only
	}

	public void free() {
		// Do nothing
	}

	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

	public Bucket createShadow() {
		if(buf.length < 256*1024) {
			byte[] newBuf = new byte[length];
			System.arraycopy(buf, offset, newBuf, 0, length);
			return new SimpleReadOnlyArrayBucket(newBuf);
		}
		return null;
	}

}
