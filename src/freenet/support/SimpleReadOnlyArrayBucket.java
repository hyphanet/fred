package freenet.support;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import freenet.client.async.ClientContext;
import freenet.support.api.Bucket;
import freenet.support.api.LockableRandomAccessBuffer;
import freenet.support.api.RandomAccessBucket;
import freenet.support.io.ByteArrayRandomAccessBuffer;

/**
 * Simple read-only array bucket. Just an adapter class to save some RAM.
 * Wraps a byte[], offset, length into a Bucket. Read-only. ArrayBucket on
 * the other hand is a chain of byte[]'s.
 * 
 * Not serializable as it doesn't copy. Should only be used for short-lived hacks for that reason.
 */
public class SimpleReadOnlyArrayBucket implements Bucket, RandomAccessBucket {

    private static final long serialVersionUID = 1L;
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
    public OutputStream getOutputStreamUnbuffered() throws IOException {
        throw new IOException("Read only");
    }
    
	@Override
	public InputStream getInputStreamUnbuffered() throws IOException {
		return new ByteArrayInputStream(buf, offset, length);
	}
	
	@Override
	public InputStream getInputStream() throws IOException {
	    return getInputStreamUnbuffered();
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
	public RandomAccessBucket createShadow() {
		if(buf.length < 256*1024) {
			return new SimpleReadOnlyArrayBucket(Arrays.copyOfRange(buf, offset, offset+length));
		}
		return null;
	}

    @Override
    public void onResume(ClientContext context) {
        // Not persistent.
        throw new UnsupportedOperationException();
    }

    @Override
    public void storeTo(DataOutputStream dos) {
        // Not persistent.
        throw new UnsupportedOperationException();
    }

    @Override
    public LockableRandomAccessBuffer toRandomAccessBuffer() throws IOException {
        ByteArrayRandomAccessBuffer raf = new ByteArrayRandomAccessBuffer(buf, offset, length, true);
        raf.setReadOnly();
        return raf;
    }

}
