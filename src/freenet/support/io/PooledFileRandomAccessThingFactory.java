package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Random;

public class PooledFileRandomAccessThingFactory implements LockableRandomAccessThingFactory {
    
    private final FilenameGenerator fg;
    private final Random seedRandom;
    private volatile boolean enableCrypto;

    public PooledFileRandomAccessThingFactory(FilenameGenerator filenameGenerator, Random seedRandom) {
        fg = filenameGenerator;
        this.seedRandom = seedRandom;
    }
    
    public void enableCrypto(boolean enable) {
        this.enableCrypto = enable;
    }

    @Override
    public LockableRandomAccessThing makeRAF(long size) throws IOException {
        File file = fg.makeRandomFile();
        return new PooledRandomAccessFileWrapper(file, "rw", size, enableCrypto ? seedRandom : null);
    }

    @Override
    public LockableRandomAccessThing makeRAF(byte[] initialContents, int offset, int size)
            throws IOException {
        File file = fg.makeRandomFile();
        return new PooledRandomAccessFileWrapper(file, "rw", initialContents, offset, size);
    }

}
