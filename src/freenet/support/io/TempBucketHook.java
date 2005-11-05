package freenet.support.io;

import java.io.IOException;

public interface TempBucketHook {
    
    /** Allocate space for a write making the file larger
     * Call this before writing a file, make sure you call shrinkFile if the write fails
     * @param curLength the length of the file before the write
     * @param finalLength the length of the file after the write
     * @throws IOException if insufficient space
     */
    void enlargeFile(long curLength, long finalLength) throws IOException;
    
    /** Deallocate space after a write enlarging the file fails
     * Call this if enlargeFile was called but the write failed.
     * Also call it if you want to truncate a file
     * @param curLength original length before write
     * @param finalLength length the file would have been if the write had succeeded
     */
    void shrinkFile(long curLength, long finalLength);
    
    /** Deallocate space for a temp file, AFTER successful delete completed
     */
    void deleteFile(long curLength);
    
    /** Allocate space for a temp file, before actually creating it
     */
    void createFile(long curLength) throws IOException;

}
