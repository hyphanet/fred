package freenet.client.async;

import java.io.DataOutputStream;
import java.io.IOException;

/** Fetches which may be persistent need getClientDetail() so that we can save that data to the 
 * file a splitfile download is using, so that it can be recovered later.
 * @author toad
 */
public interface PersistentClientCallback extends ClientBaseCallback {

    /**
     * Called to get a representation of the client, e.g. for FCP this might include the 
     * identifier, whether it is on the global queue, and the client name. This is written to the
     * file storing the status of a persistent splitfile download, for easier recovery. It is legal
     * to return an empty byte[0], and any non-empty implementation must contain a magic marker and 
     * version number. Will only be called for persistent requests. Should not include progress 
     * etc; this is supposed to be just enough to restart the download from scratch.
     */
    public void getClientDetail(DataOutputStream dos) throws IOException;
    
}
