package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Random;

/** Creates RandomAccessBuffer's from File's. */
public interface FileRandomAccessBufferFactory {
    
    public PooledFileRandomAccessBuffer createNewRAF(File file, long size, Random random) throws IOException;

}
