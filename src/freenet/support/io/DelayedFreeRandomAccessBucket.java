/**
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */
package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.crypt.MasterSecret;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.LockableRandomAccessBuffer;
import freenet.support.api.RandomAccessBucket;

public class DelayedFreeRandomAccessBucket implements Bucket, Serializable, RandomAccessBucket, DelayedFree {

    private static final long serialVersionUID = 1L;
    // Only set on construction and on onResume() on startup. So shouldn't need locking.
	private transient PersistentFileTracker factory;
	private final RandomAccessBucket bucket;
	private boolean freed;
	private transient long createdCommitID;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	@Override
	public boolean toFree() {
		return freed;
	}
	
	public DelayedFreeRandomAccessBucket(PersistentFileTracker factory, RandomAccessBucket bucket) {
		this.factory = factory;
		this.bucket = bucket;
		this.createdCommitID = factory.commitID();
		if(bucket == null) throw new NullPointerException();
	}

    @Override
	public OutputStream getOutputStream() throws IOException {
        synchronized(this) {
            if(freed) throw new IOException("Already freed");
        }
		return bucket.getOutputStream();
	}

    @Override
    public OutputStream getOutputStreamUnbuffered() throws IOException {
        synchronized(this) {
            if(freed) throw new IOException("Already freed");
        }
        return bucket.getOutputStreamUnbuffered();
    }

	@Override
	public InputStream getInputStream() throws IOException {
	    synchronized(this) {
	        if(freed) throw new IOException("Already freed");
	    }
		return bucket.getInputStream();
	}

    @Override
    public InputStream getInputStreamUnbuffered() throws IOException {
        synchronized(this) {
            if(freed) throw new IOException("Already freed");
        }
        return bucket.getInputStreamUnbuffered();
    }

	@Override
	public String getName() {
		return bucket.getName();
	}

	@Override
	public long size() {
		return bucket.size();
	}

	@Override
	public boolean isReadOnly() {
		return bucket.isReadOnly();
	}

	@Override
	public void setReadOnly() {
		bucket.setReadOnly();
	}

    public synchronized Bucket getUnderlying() {
		if(freed) return null;
		return bucket;
	}
	
	@Override
	public void free() {
	    synchronized(this) {
	        if(freed) return;
	        freed = true;
	    }
	    if(logMINOR)
	        Logger.minor(this, "Freeing "+this+" underlying="+bucket, new Exception("debug"));
	    this.factory.delayedFree(this, createdCommitID);
	}

	@Override
	public String toString() {
		return super.toString()+":"+bucket;
	}
	
	@Override
	public RandomAccessBucket createShadow() {
		return bucket.createShadow();
	}

	@Override
    public void realFree() {
		bucket.free();
	}

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        this.factory = context.persistentBucketFactory;
        bucket.onResume(context);
    }
    
    static final int MAGIC = 0xa28f2a2d;
    static final int VERSION = 1;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(VERSION);
        bucket.storeTo(dos);
    }

    protected DelayedFreeRandomAccessBucket(DataInputStream dis, FilenameGenerator fg, 
            PersistentFileTracker persistentFileTracker, MasterSecret masterKey) 
    throws StorageFormatException, IOException, ResumeFailedException {
        int version = dis.readInt();
        if(version != VERSION) throw new StorageFormatException("Bad version");
        bucket = (RandomAccessBucket) BucketTools.restoreFrom(dis, fg, persistentFileTracker, masterKey);
    }

    @Override
    public LockableRandomAccessBuffer toRandomAccessBuffer() throws IOException {
        synchronized(this) {
            if(freed) throw new IOException("Already freed");
        }
        setReadOnly();
        return new DelayedFreeRandomAccessBuffer(bucket.toRandomAccessBuffer(), factory);
    }

}