package freenet.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.api.Bucket;

/**
 * Simple read-only array bucket. Just an adapter class to save some RAM.
 * Not the same as ArrayBucket, which can't take a (byte[], offset, len) in
 * constructor (unless we waste some RAM there by using an object to store these
 * instead of storing the byte[]'s directly).
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

}
