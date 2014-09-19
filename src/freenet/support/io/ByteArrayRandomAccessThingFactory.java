package freenet.support.io;

import java.io.IOException;
import java.util.Arrays;

import freenet.support.api.LockableRandomAccessThing;

public class ByteArrayRandomAccessThingFactory implements LockableRandomAccessThingFactory {

    @Override
    public LockableRandomAccessThing makeRAF(long size) throws IOException {
        if(size < 0) throw new IllegalArgumentException();
        if(size > Integer.MAX_VALUE) throw new IOException("Too big");
        byte[] buf = new byte[(int)size];
        return new ByteArrayRandomAccessThing(buf);
    }

    @Override
    public LockableRandomAccessThing makeRAF(byte[] initialContents, int offset, int size, boolean readOnly)
            throws IOException {
        if(size < 0) throw new IllegalArgumentException();
        return new ByteArrayRandomAccessThing(Arrays.copyOfRange(initialContents, offset, 
                offset+size), 0, size, readOnly);
    }

}
