package freenet.support.io;

import java.io.IOException;

public class ByteArrayRandomAccessThingFactory implements LockableRandomAccessThingFactory {

    @Override
    public LockableRandomAccessThing makeRAF(long size) throws IOException {
        if(size < 0) throw new IllegalArgumentException();
        if(size > Integer.MAX_VALUE) throw new IOException("Too big");
        byte[] buf = new byte[(int)size];
        return new ByteArrayRandomAccessThing(buf);
    }

}
