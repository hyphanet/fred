package freenet.support.io;

import java.io.File;

public class TrivialPersistentFileTracker implements PersistentFileTracker {
    
    final File dir;
    final FilenameGenerator fg;

    public TrivialPersistentFileTracker(File dir, FilenameGenerator fg) {
        this.dir = dir;
        this.fg = fg;
    }

    @Override
    public void register(File file) {
        // Ignore.
    }

    @Override
    public void delayedFree(DelayedFree bucket, long commitID) {
        bucket.realFree();
    }

    @Override
    public File getDir() {
        return dir;
    }

    @Override
    public FilenameGenerator getGenerator() {
        return fg;
    }

    @Override
    public boolean checkDiskSpace(File file, int toWrite, int bufferSize) {
        return true;
    }

    @Override
    public long commitID() {
        return 1;
    }

}
