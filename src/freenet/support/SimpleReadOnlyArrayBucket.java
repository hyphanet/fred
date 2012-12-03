package freenet.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

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
	
	@Override
	public OutputStream getOutputStream() throws IOException {
		throw new IOException("Read only");
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return new ByteArrayInputStream(buf, offset, length);
	}

	@Override
	public String getName() {
		return "SimpleReadOnlyArrayBucket: len="+length+ ' ' +super.toString();
	}

	@Override
	public long size() {
		return length;
	}

	@Override
	public boolean isReadOnly() {
		return true;
	}

	@Override
	public void setReadOnly() {
		// Already read-only
	}

	@Override
	public void free() {
		// Do nothing
	}

	@Override
	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

	@Override
	public Bucket createShadow() {
		if(buf.length < 256*1024) {
			return new SimpleReadOnlyArrayBucket(Arrays.copyOfRange(buf, offset, offset+length));
		}
		return null;
	}

}
