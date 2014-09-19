package freenet.support.api;

import java.io.IOException;

import freenet.support.io.LockableRandomAccessThing;

/** A Bucket which can be converted to a LockableRandomAccessThing without copying. Mostly we need 
 * this where the size of something we will later use as a RandomAccessThing is uncertain. It 
 * provides a separate object because the API's are incompatible; in particular, the size of a 
 * RandomAccessThing is fixed (and this is mostly a good thing). */
public interface RandomAccessBucket extends Bucket {
    
    /** Convert the Bucket to a LockableRandomAccessThing. Must be efficient, i.e. will not copy 
     * the data. Freeing the Bucket is unnecessary if you free the LockableRandomAccessThing. 
     * Both the parent Bucket and the return value will be made read only.
     * @throws IOException */
    public LockableRandomAccessThing toRandomAccessThing() throws IOException;
    
    @Override
    public RandomAccessBucket createShadow();

}
