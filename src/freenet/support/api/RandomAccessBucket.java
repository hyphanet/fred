package freenet.support.api;

import java.io.IOException;


/** A Bucket which can be converted to a LockableRandomAccessBuffer without copying. Mostly we need 
 * this where the size of something we will later use as a RandomAccessBuffer is uncertain. It 
 * provides a separate object because the API's are incompatible; in particular, the size of a 
 * RandomAccessBuffer is fixed (and this is mostly a good thing). */
public interface RandomAccessBucket extends Bucket {
    
    /** Convert the Bucket to a LockableRandomAccessBuffer. Must be efficient, i.e. will not copy 
     * the data. Freeing the Bucket is unnecessary if you free the LockableRandomAccessBuffer. 
     * Both the parent Bucket and the return value will be made read only.
     * @throws IOException */
    public LockableRandomAccessBuffer toRandomAccessThing() throws IOException;
    
    @Override
    public RandomAccessBucket createShadow();

}
