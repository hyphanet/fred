package freenet.support.io;

import java.io.IOException;
import java.util.Arrays;

import freenet.support.api.LockableRandomAccessBuffer;
import freenet.support.api.LockableRandomAccessBufferFactory;

public class ByteArrayRandomAccessBufferFactory implements LockableRandomAccessBufferFactory {

    @Override
    public LockableRandomAccessBuffer makeRAF(long size) throws IOException {
        if(size < 0) throw new IllegalArgumentException();
        if(size > Integer.MAX_VALUE) throw new IOException("Too big");
        byte[] buf = new byte[(int)size];
        return new ByteArrayRandomAccessBuffer(buf);
    }

    @Override
    public LockableRandomAccessBuffer makeRAF(byte[] initialContents, int offset, int size, boolean readOnly)
            throws IOException {
        if(size < 0) throw new IllegalArgumentException();
        return new ByteArrayRandomAccessBuffer(Arrays.copyOfRange(initialContents, offset, 
                offset+size), 0, size, readOnly);
    }

}
