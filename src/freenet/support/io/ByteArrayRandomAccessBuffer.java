package freenet.support.io;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;

import freenet.client.async.ClientContext;
import freenet.support.api.LockableRandomAccessBuffer;

public class ByteArrayRandomAccessBuffer implements LockableRandomAccessBuffer, Serializable {

    private static final long serialVersionUID = 1L;
    private final byte[] data;
	private boolean readOnly;
	private boolean closed;
	
	public ByteArrayRandomAccessBuffer(byte[] padded) {
		this.data = padded;
	}
	
	public ByteArrayRandomAccessBuffer(int size) {
	    this.data = new byte[size];
	}

	public ByteArrayRandomAccessBuffer(byte[] initialContents, int offset, int size, boolean readOnly) {
	    data = Arrays.copyOfRange(initialContents, offset, offset+size);
	    this.readOnly = readOnly;
    }
	
	protected ByteArrayRandomAccessBuffer() {
	    // For serialization.
	    data = null;
	}

    @Override
	public void close() {
	    closed = true;
	}

	@Override
	public synchronized void pread(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
	    if(closed) throw new IOException("Closed");
		if(fileOffset < 0) throw new IllegalArgumentException("Cannot read before zero");
		if(fileOffset + length > data.length) throw new IOException("Cannot read after end: trying to read from "+fileOffset+" to "+(fileOffset+length)+" on block length "+data.length);
		System.arraycopy(data, (int)fileOffset, buf, bufOffset, length);
	}

	@Override
	public synchronized void pwrite(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
        if(closed) throw new IOException("Closed");
		if(fileOffset < 0) throw new IllegalArgumentException("Cannot write before zero");
		if(fileOffset + length > data.length) throw new IOException("Cannot write after end: trying to write from "+fileOffset+" to "+(fileOffset+length)+" on block length "+data.length);
		if(readOnly) throw new IOException("Read-only");
		System.arraycopy(buf, bufOffset, data, (int)fileOffset, length);
	}

	@Override
	public long size() {
		return data.length;
	}

	public synchronized void setReadOnly() {
		readOnly = true;
	}
	
    public synchronized boolean isReadOnly() {
        return readOnly;
    }
    
    @Override
    public RAFLock lockOpen() {
        return new RAFLock() {

            @Override
            protected void innerUnlock() {
                // Do nothing. Always open.
            }
            
        };
    }

    @Override
    public void free() {
        // Do nothing.
    }
    
    /** Package-local! */
    byte[] getBuffer() {
        return data;
    }

    @Override
    public void onResume(ClientContext context) {
        // Do nothing.
    }

    @Override
    public void storeTo(DataOutputStream dos) {
        throw new UnsupportedOperationException();
    }

    // Default hashCode() and equals() are correct for this type.

}
