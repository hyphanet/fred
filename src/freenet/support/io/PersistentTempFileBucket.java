package freenet.support.io;

import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.api.Bucket;

public class PersistentTempFileBucket extends TempFileBucket implements Serializable {

    private static final long serialVersionUID = 1L;
    
    transient PersistentFileTracker tracker;

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
	protected boolean deleteOnFinalize() {
		// Do not delete on finalize
		return false;
	}
	
	@Override
	protected boolean deleteOnExit() {
		// DO NOT DELETE ON EXIT !!!!
		return false;
	}
	
	/** Must override createShadow() so it creates a persistent bucket, which will have
	 * deleteOnExit() = deleteOnFinalize() = false.
	 */
	@Override
	public Bucket createShadow() {
		PersistentTempFileBucket ret = new PersistentTempFileBucket(filenameID, generator, tracker, false);
		ret.setReadOnly();
		if(!getFile().exists()) Logger.error(this, "File does not exist when creating shadow: "+getFile());
		return ret;
	}
	
    @Override
    public void onResume(ClientContext context) {
        // Ewww writing parent's field.
        generator = context.persistentFG;
        tracker = context.persistentFileTracker;
        tracker.register(getFile());
    }
	
}
