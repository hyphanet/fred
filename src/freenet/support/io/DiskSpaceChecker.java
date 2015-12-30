package freenet.support.io;

import java.io.File;

public interface DiskSpaceChecker {
    
    /** Is there enough space to extend the file?
     * @param file The current filename
     * @param toWrite Length of the proposed write
     * @param bufferSize The caller only checks disk space when the number of bytes written since 
     * the last check exceeds this value.
     * @return True if there is sufficient disk space
     */
    public boolean checkDiskSpace(File file, int toWrite, int bufferSize);

}
