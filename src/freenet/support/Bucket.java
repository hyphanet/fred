package freenet.support;
import java.io.*;
/**
 * A bucket is any arbitrary object can temporarily store data.
 * 
 * @author oskar
 */
public interface Bucket {

    /**
     * Returns an OutputStream that is used to put data in this Bucket.
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
     * If resetWrite() is called on the object, the next getOutputStream
     * should overwrite any other data in the bucket from the beginning,
     * otherwise it should append it.
     */
    public void resetWrite() throws IOException;

    /**
     * Returns the amount of data currently in this bucket.
     */
    public long size();

    /**
     * Convert the contents of the bucket to a byte array.
     * Don't use this unless you know the bucket is small!
     */
	public byte[] toByteArray();

}




