/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.db4o.ObjectContainer;
/**
 * A bucket is any arbitrary object can temporarily store data. In other 
 * words, it is the equivalent of a temporary file, but it could be in RAM,
 * on disk, encrypted, part of a file on disk, composed from a chain of 
 * other buckets etc.
 * 
 * @author oskar
 */
public interface Bucket {

    /**
     * Returns an OutputStream that is used to put data in this Bucket, from the 
     * beginning. It is not possible to append data to a Bucket! This simplifies the
     * code significantly for some classes. If you need to append, just pass the 
     * OutputStream around.
     */
    public OutputStream getOutputStream() throws IOException;

    /**
     * Returns an InputStream that reads data from this Bucket. If there is
     * no data in this bucket, null is returned.
     * 
     * You have to call Closer.close(inputStream) on the obtained stream to prevent resource leakage.
     */
    public InputStream getInputStream() throws IOException;

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
     * Free the bucket, if supported.
     */
	public void free();
	
	/**
	 * Write the bucket and all its dependancies to the database.
	 * Update the stored copy and its dependancies if necessary.
	 */
	public void storeTo(ObjectContainer container);

	/**
	 * Remove the bucket and everything under it from the database.
	 * You don't need to call this if it hasn't been storeTo()'ed: buckets 
	 * that use the database internally will run a blocking job to delete 
	 * internal structure in free().
	 * @param container The database.
	 */
	public void removeFrom(ObjectContainer container);

	/**
	 * Create a shallow read-only copy of this bucket, using different 
	 * objects but using the same external storage. If this is not possible, 
	 * return null. Note that if the underlying bucket is deleted, the copy
	 * will become invalid and probably throw an IOException on read, or 
	 * possibly return too-short data etc. In some use cases e.g. on fproxy, 
	 * this is acceptable.
	 */
	public Bucket createShadow();

}
