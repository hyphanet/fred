package freenet.support.api;

import java.io.IOException;


/** Serves a similar function to BucketFactory. Different factories may serve different functions 
 * e.g. temporary storage (not persistent across restarts) only.
 * @author toad
 */
public interface LockableRandomAccessBufferFactory {
    
    /**
     * Create a bucket.
     * @param size The maximum size of the data. Most factories will pre-allocate this space, and 
     * it may not be exceeded. However we do not guarantee that any I/O operation will complete; 
     * even if we have pre-allocated the disk space, we may be unable to write to it because of 
     * e.g. a hardware error.
     * @return A LockableRandomAccessBuffer of the requested size.
     * @throws IOException If an I/O error prevented the operation.
     * @throws IllegalArgumentException If size < 0.
     */
    public LockableRandomAccessBuffer makeRAF(long size) throws IOException;
    
    /**
     * Create a bucket with specified initial contents.
     * @param initialContents Byte array from which to copy data. Data will be copied even if the
     * underlying implementation is a byte array, for reasons of consistency.
     * @param offset Offset within the array to start copying data from.
     * @param size Number of bytes to copy i.e. length of the new RandomAccessBuffer.
     * @return
     * @throws IOException If an I/O error prevented the operation.
     */
    public LockableRandomAccessBuffer makeRAF(byte[] initialContents, int offset, int size, boolean readOnly) throws IOException;

}
