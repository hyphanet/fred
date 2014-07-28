package freenet.support.io;

import java.io.File;
import java.io.IOException;

public class PooledFileRandomAccessThingFactory implements LockableRandomAccessThingFactory {
    
    private final FilenameGenerator fg;

    public PooledFileRandomAccessThingFactory(FilenameGenerator filenameGenerator) {
        fg = filenameGenerator;
    }

    @Override
    public LockableRandomAccessThing makeRAF(long size) throws IOException {
        File file = fg.makeRandomFile();
        return new PooledRandomAccessFileWrapper(file, "rw", size);
    }

    @Override
    public LockableRandomAccessThing makeRAF(byte[] initialContents, int offset, int size)
            throws IOException {
        File file = fg.makeRandomFile();
        return new PooledRandomAccessFileWrapper(file, "rw", initialContents, offset, size);
    }

}
