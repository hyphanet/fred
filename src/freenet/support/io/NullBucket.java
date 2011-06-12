/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;
import java.io.InputStream;
import java.io.OutputStream;

import com.db4o.ObjectContainer;

import freenet.support.api.Bucket;

public class NullBucket implements Bucket {

    public final OutputStream nullOut = new NullOutputStream();
    public final InputStream  nullIn  = new NullInputStream();

    public final long length;
    
    public NullBucket() {
        this(0);
    }

    public NullBucket(long length) {
        this.length = length;
    }
    
    /**
     * Returns an OutputStream that is used to put data in this Bucket.
     **/
    @Override
    public OutputStream getOutputStream() { return nullOut; }

    /**
     * Returns an InputStream that reads data from this Bucket. If there is
     * no data in this bucket, null is returned.
     **/
    @Override
    public InputStream getInputStream() { return nullIn; }

    /**
     * Returns the amount of data currently in this bucket.
     **/
    @Override
    public long size() {
        return length;
    }

    /** Returns the name of this NullBucket. */
    @Override
    public String getName() {
    	return "President George W. NullBucket";
    }

	@Override
	public boolean isReadOnly() {
		return false;
	}

	@Override
	public void setReadOnly() {
		// Do nothing
	}

	@Override
	public void free() {
		// Do nothing
	}

	@Override
	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

	@Override
	public Bucket createShadow() {
		return new NullBucket();
	}
}

