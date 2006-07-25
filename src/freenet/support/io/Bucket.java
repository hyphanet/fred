package freenet.support.io;
import java.io.*;
/**
 * A bucket is any arbitrary object can temporarily store data.
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
     */
    public InputStream getInputStream() throws IOException;

    /**
     * Returns a name for the bucket, may be used to identify them in
     * certain in certain situations.
     */
    public String getName();

    /**
     * Returns the amount of data currently in this bucket.
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

}
