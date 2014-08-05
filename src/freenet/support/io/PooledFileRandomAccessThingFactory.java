package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Random;

/** Creates temporary RAFs using a FilenameGenerator. */
public class PooledFileRandomAccessThingFactory implements LockableRandomAccessThingFactory {
    
    private final FilenameGenerator fg;
    private final Random seedRandom;
    private volatile boolean enableCrypto;
    private final boolean persistentTemp;

    public PooledFileRandomAccessThingFactory(FilenameGenerator filenameGenerator, Random seedRandom, boolean persistentTemp) {
        fg = filenameGenerator;
        this.seedRandom = seedRandom;
        this.persistentTemp = persistentTemp;
    }
    
    public void enableCrypto(boolean enable) {
        this.enableCrypto = enable;
    }

    @Override
    public LockableRandomAccessThing makeRAF(long size) throws IOException {
        File file = fg.makeRandomFile();
        return new PooledRandomAccessFileWrapper(file, "rw", size, enableCrypto ? seedRandom : null, persistentTemp);
    }

    @Override
    public LockableRandomAccessThing makeRAF(byte[] initialContents, int offset, int size)
            throws IOException {
        File file = fg.makeRandomFile();
        return new PooledRandomAccessFileWrapper(file, "rw", initialContents, offset, size, persistentTemp);
    }

}
