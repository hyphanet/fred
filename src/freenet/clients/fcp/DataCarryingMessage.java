/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.client.async.PersistenceDisabledException;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.RandomAccessBucket;
import freenet.support.io.BucketTools;
import freenet.support.io.FileUtil;
import freenet.support.io.NullBucket;
import freenet.support.io.NullOutputStream;


public abstract class DataCarryingMessage extends BaseDataCarryingMessage {

    /** If this is a message from the client, then the Bucket was created by createBucket() and 
     * will be a RandomAccessBucket. However if it is a message we are sending to the client, it 
     * may not be. FIXME split up into two classes? */
	protected Bucket bucket;
	
	RandomAccessBucket createBucket(BucketFactory bf, long length, FCPServer server) throws IOException, PersistenceDisabledException {
		return bf.makeBucket(length);
	}
	
	abstract String getIdentifier();
	abstract boolean isGlobal();

	protected boolean freeOnSent;
	
	void setFreeOnSent() {
		freeOnSent = true;
	}

	@Override
	public void readFrom(InputStream is, BucketFactory bf, FCPServer server) throws IOException, MessageInvalidException {
		long len = dataLength();
		if(len < 0) return;
		if(len == 0) {
			bucket = new NullBucket();
			return;
		}
		RandomAccessBucket tempBucket;
		try {
			tempBucket = createBucket(bf, len, server);
		} catch (IOException e) {
			Logger.error(this, "Bucket error: "+e, e);
			FileUtil.copy(is, new NullOutputStream(), len);
			throw new MessageInvalidException(ProtocolErrorMessage.INTERNAL_ERROR, e.toString(), getIdentifier(), isGlobal());
		} catch (PersistenceDisabledException e) {
            Logger.error(this, "Bucket error: "+e, e);
            FileUtil.copy(is, new NullOutputStream(), len);
            throw new MessageInvalidException(ProtocolErrorMessage.PERSISTENCE_DISABLED, null, getIdentifier(), isGlobal());
        }
		BucketTools.copyFrom(tempBucket, is, len);
		this.bucket = tempBucket;
	}
	
	@Override
	protected void writeData(OutputStream os) throws IOException {
		long len = dataLength();
		if(len > 0) BucketTools.copyTo(bucket, os, len);
		if(freeOnSent) bucket.free(); // Always transient so no removeFrom() needed.
	}
	
	@Override
	String getEndString() {
		return "Data";
	}
	
	/** Should only be called from code parsing a message sent to us, in which case Bucket will be a
     * RandomAccessBucket, which it needs to be as it's likely to be inserted. If we are sending a 
     * message to the client, it might not be. FIXME split up into two classes? */
    public RandomAccessBucket getRandomAccessBucket() {
        return (RandomAccessBucket) bucket;
    }
	
}
