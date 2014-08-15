package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Random;

/** Creates temporary RAFs using a FilenameGenerator. */
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
        long id = fg.makeRandomFilename();
        File file = fg.getFilename(id);
        LockableRandomAccessThing ret = null;
        try {
            ret = new PooledRandomAccessFileWrapper(file, false, size, enableCrypto ? seedRandom : null, id);
            return ret;
        } finally {
            if(ret == null) file.delete();
        }
    }

    @Override
    public LockableRandomAccessThing makeRAF(byte[] initialContents, int offset, int size)
            throws IOException {
        long id = fg.makeRandomFilename();
        File file = fg.getFilename(id);
        LockableRandomAccessThing ret = null;
        try {
            ret = new PooledRandomAccessFileWrapper(file, "rw", initialContents, offset, size, id);
            return ret;
        } finally {
            if(ret == null) file.delete();
        }
    }

}
