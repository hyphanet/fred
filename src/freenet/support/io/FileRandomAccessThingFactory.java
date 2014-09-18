package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Random;

/** Creates RandomAccessThing's from File's. */
public interface FileRandomAccessThingFactory {
    
    public PooledRandomAccessFileWrapper createNewRAF(File file, long size, Random random) throws IOException;

}
