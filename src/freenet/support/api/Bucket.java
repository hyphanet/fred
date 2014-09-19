/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.client.async.ClientContext;
import freenet.support.io.ResumeFailedException;
/**
 * A bucket is any arbitrary object can temporarily store data. In other 
 * words, it is the equivalent of a temporary file, but it could be in RAM,
 * on disk, encrypted, part of a file on disk, composed from a chain of 
 * other buckets etc.
 * 
 * Not all buckets are Serializable.
 * 
 * @author oskar
 */
public interface Bucket {

    /**
     * Returns an OutputStream that is used to put data in this Bucket, from the 
     * beginning. It is not possible to append data to a Bucket! This simplifies the
     * code significantly for some classes. If you need to append, just pass the 
     * OutputStream around. Will be buffered if appropriate (e.g. byte array backed
     * buckets don't need to be buffered).
     */
    public OutputStream getOutputStream() throws IOException;
    
    /** Get an OutputStream which is not buffered. Should be called when we will buffer the stream 
     * at a higher level or when we will only be doing large writes (e.g. copying data from one 
     * Bucket to another). Does not make any more persistence guarantees than getOutputStream() 
     * does, this is just to save memory.
     */
    public OutputStream getOutputStreamUnbuffered() throws IOException;

    /**
     * Returns an InputStream that reads data from this Bucket. If there is
     * no data in this bucket, null is returned.
     * 
     * You have to call Closer.close(inputStream) on the obtained stream to prevent resource leakage.
     */
    public InputStream getInputStream() throws IOException;

    public InputStream getInputStreamUnbuffered() throws IOException;
    
    /**
     * Returns a name for the bucket, may be used to identify them in
     * certain in certain situations.
     */
    public String getName();

    /**
     * Returns the amount of data currently in this bucket in bytes.
     */
    public long size();

    /**
     * Is the bucket read-only?
     */
    public boolean isReadOnly();
    
    /**
     * Make the bucket read-only. Irreversible.
     */
    public void setReadOnly();

    /**
     * Free the bucket, if supported. Note that you must call free() even if you haven't used the 
     * Bucket (haven't called getOutputStream()) for some kinds of Bucket's, as they may have
     * allocated space (e.g. created a temporary file).
     */
	public void free();
	
	/**
	 * Create a shallow read-only copy of this bucket, using different 
	 * objects but using the same external storage. If this is not possible, 
	 * return null. Note that if the underlying bucket is deleted, the copy
	 * will become invalid and probably throw an IOException on read, or 
	 * possibly return too-short data etc. In some use cases e.g. on fproxy, 
	 * this is acceptable.
	 */
	public Bucket createShadow();

	/** Called after restarting. The Bucket should do any necessary housekeeping after resuming,
	 * e.g. registering itself with the appropriate persistent bucket tracker to avoid being 
	 * garbage-collected.  May be called twice, so the Bucket may need to track this internally.
	 * @param context All the necessary runtime support will be on this object. 
	 * @throws ResumeFailedException */
	public void onResume(ClientContext context) throws ResumeFailedException;

	/** Write enough data to reconstruct the Bucket, or throw UnsupportedOperationException. Used
	 * for recovering in emergencies, should be versioned if necessary. 
	 * @throws IOException */
    public void storeTo(DataOutputStream dos) throws IOException;

}
