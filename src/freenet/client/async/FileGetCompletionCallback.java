package freenet.client.async;

import java.io.File;

import freenet.client.ClientMetadata;

/** If a request will return the downloaded data to a file (not a temporary file), and if the data
 * doesn't need decompressing or filtering, and if we are doing the final stage of the download, we
 * can create a file in the same directory and use it for temporary storage, then when the download
 * completes call onSuccess(), which will check the hashes and then rename the file.
 * 
 * This saves us a lot of copying, disk I/O and (peak) disk space.
 * 
 * The ClientGetState calling the callback must only use this interface if it is the final fetch.
 * @author toad
 */
public interface FileGetCompletionCallback extends GetCompletionCallback {

    /** Get the final location of the downloaded data. If this returns non-null, the caller may
     * create a temporary file in the same directory and use it for e.g. storing the downloaded
     * splitfile blocks. When complete, the caller should truncate the file to the correct length,
     * and then call onSuccess().
     * @return The final target File, or null if the download isn't to a (non-temporary) file, 
     * the target file can't be used for temporary storage etc. The returned file must be absolute.
     */
    public File getCompletionFile();
    
    /** Call when the download has completed and the tempFile contains the downloaded data, but
     * may be too long. The callback must truncate the file, check the hashes on the file and 
     * complete the request.
     * @param tempFile A file in the same directory as the completion file, containing the 
     * downloaded data.
     * @param length The length of the downloaded data.
     * @param metadata The MIME type of the downloaded data.
     * @param state The calling ClientGetState.
     * @param context Contains run-time support structures such as executors, temporary storage
     * factories etc. Not static because we want to be able to run multiple nodes in one VM for
     * tests etc.
     */
    public void onSuccess(File tempFile, long length, ClientMetadata metadata, 
            ClientGetState state, ClientContext context);
    
}
