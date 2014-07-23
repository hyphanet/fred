package freenet.support.io;

import java.io.IOException;

/** Serves a similar function to BucketFactory. Different factories may serve different functions 
 * e.g. temporary storage (not persistent across restarts) only.
 * @author toad
 */
public interface LockableRandomAccessThingFactory {
    
    /**
     * Create a bucket.
     * @param size The maximum size of the data. Most factories will pre-allocate this space, and 
     * it may not be exceeded. However we do not guarantee that any I/O operation will complete; 
     * even if we have pre-allocated the disk space, we may be unable to write to it because of 
     * e.g. a hardware error.
     * @return A LockableRandomAccessThing of the requested size.
     * @throws IOException If an I/O error prevented the operation.
     * @throws IllegalArgumentException If size < 0.
     */
    public LockableRandomAccessThing makeRAF(long size) throws IOException;

}
