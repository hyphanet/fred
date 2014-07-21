package freenet.client.async;

import java.io.IOException;

/** Callback used by SplitFileFetcherStorage. Arguably this is over-abstraction purely to make unit
 * tests easier without having to use Mockito (which presumably means solving the issues with 
 * Maven). OTOH maybe it has some other use ... FIXME reconsider.
 * @author toad
 */
public interface SplitFileFetcherCallback {

    /** Called when the splitfile has been successfully downloaded and decoded. E.g. 
     * streamGenerator() should work now. However the splitfile storage layer may still need the
     * data to e.g. encode healing blocks, so it cannot be freed until close(). The higher level
     * code (e.g. SplitFileFetcherNew) must call finishedFetcher() when finished, and when that
     * has been called *and* the storage layer has finished, it will close and free the underlying 
     * storage and call close() here. */
    void onSuccess();
    
    /** Called when the storage layer has finished, the higher level code has finished, and the 
     * storage has been freed, i.e. the request is now completely finished. */
    void close();

    /** Get the priority class of the request. Needed for e.g. FEC decoding scheduling. */
    short getPriorityClass();

    /** Called when the splitfile storage layer receives an unrecoverable disk I/O error. */
    void failOnDiskError(IOException e);

}
