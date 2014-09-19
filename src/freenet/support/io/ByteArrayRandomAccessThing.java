package freenet.support.io;

import java.io.IOException;

public class ByteArrayRandomAccessThing implements LockableRandomAccessThing {

	private final byte[] data;
	private boolean readOnly;
	private boolean closed;
	
	public ByteArrayRandomAccessThing(byte[] padded) {
		this.data = padded;
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
	public long size() throws IOException {
		return data.length;
	}

	public synchronized void setReadOnly() {
		readOnly = true;
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

}
