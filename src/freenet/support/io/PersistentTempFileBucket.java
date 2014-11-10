package freenet.support.io;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.api.RandomAccessBucket;

public class PersistentTempFileBucket extends TempFileBucket implements Serializable {

    private static final long serialVersionUID = 1L;
    
    transient PersistentFileTracker tracker;
    
    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(PersistentTempFileBucket.class);
    }

    public PersistentTempFileBucket(long id, FilenameGenerator generator, PersistentFileTracker tracker) {
		this(id, generator, tracker, true);
	}
	
	protected PersistentTempFileBucket(long id, FilenameGenerator generator, PersistentFileTracker tracker, boolean deleteOnFree) {
		super(id, generator, deleteOnFree);
		this.tracker = tracker;
	}
	
	protected PersistentTempFileBucket() {
	    // For serialization.
	}
	
	@Override
	protected boolean deleteOnExit() {
		// DO NOT DELETE ON EXIT !!!!
		return false;
	}
	
	static final int BUFFER_SIZE = 4096;
	
	@Override
	public OutputStream getOutputStreamUnbuffered() throws IOException {
	    OutputStream os = super.getOutputStreamUnbuffered();
	    os = new DiskSpaceCheckingOutputStream(os, tracker, getFile(), BUFFER_SIZE);
	    return os;
	}
	
	@Override
	public OutputStream getOutputStream() throws IOException {
	    return new BufferedOutputStream(getOutputStreamUnbuffered(), BUFFER_SIZE);
	}
	
	/** Must override createShadow() so it creates a persistent bucket, which will have
	 * deleteOnExit() = deleteOnFinalize() = false.
	 */
	@Override
	public RandomAccessBucket createShadow() {
		PersistentTempFileBucket ret = new PersistentTempFileBucket(filenameID, generator, tracker, false);
		ret.setReadOnly();
		if(!getFile().exists()) Logger.error(this, "File does not exist when creating shadow: "+getFile());
		return ret;
	}
	
    @Override
    protected void innerResume(ClientContext context) throws ResumeFailedException {
        super.innerResume(context);
        if(logMINOR) Logger.minor(this, "Resuming "+this, new Exception("debug"));
        tracker = context.persistentFileTracker;
        tracker.register(getFile());
    }
    
    @Override
    protected boolean persistent() {
        return true;
    }
    
    public static final int MAGIC = 0x2ffdd4cf;
    
    protected int magic() {
        return MAGIC;
    }
    
    protected PersistentTempFileBucket(DataInputStream dis) throws IOException, StorageFormatException {
        super(dis);
    }

    @Override
    protected long getPersistentTempID() {
        return filenameID;
    }

}
