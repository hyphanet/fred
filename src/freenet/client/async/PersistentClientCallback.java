package freenet.client.async;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.crypt.ChecksumChecker;

/** Fetches which may be persistent need getClientDetail() so that we can save that data to the 
 * file a splitfile download is using, so that it can be recovered later.
 * @author toad
 */
public interface PersistentClientCallback extends ClientBaseCallback {

    /**
     * Called to get a high level representation of the request. This will include:
     * - Enough information to restart the request from scratch, including fields that can be 
     * changed manually after starting it (priority and client token).
     * - If the request has completed, full data on its completion, i.e. the MIME type, where it
     * was eventually stored etc.
     * - If the request has reached a *simple* final state, i.e. a splitfile download, enough 
     * information to resume the request. This does NOT apply to single block fetches, multi-level
     * metadata fetches, container fetches etc; it's basically the filename of the splitfile 
     * download plus the metadata that the splitfile may not have (MIME type etc).
     * 
     * This is called in two cases:
     * 1) When checkpointing, we write this data so that a request can be recovered even if 
     * serialization fails.
     * 2) When creating a splitfile, we store it, so that a request can be recovered just from the
     * splitfile. We do not update this data later on, so when it is restored it may have an out
     * of date priority or client token.
     */
    public void getClientDetail(DataOutputStream dos, ChecksumChecker checker) throws IOException;
    
}
