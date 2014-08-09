package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

/*
 *  This code is part of FProxy, an HTTP proxy server for Freenet.
 *  It is distributed under the GNU Public Licence (GPL) version 2.  See
 *  http://www.gnu.org/ for further details of the GPL.
 */
/**
 * Temporary file handling. TempFileBuckets start empty.
 *
 * @author     giannij
 */
public class TempFileBucket extends BaseFileBucket implements Bucket, Serializable {
    // Should not be serialized but we need Serializable to save the parent state for PersistentTempFileBucket.
    private static final long serialVersionUID = 1L;
    long filenameID;
	protected transient FilenameGenerator generator;
	private boolean readOnly;
	private final boolean deleteOnFree;
	private File file;

        private static volatile boolean logMINOR;
        private static volatile boolean logDEBUG;

        static {
            Logger.registerLogThresholdCallback(new LogThresholdCallback() {

                @Override
                public void shouldUpdate() {
                    logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
                    logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
                }
            });
        }
	
	public TempFileBucket(long id, FilenameGenerator generator) {
		// deleteOnExit -> files get stuck in a big HashSet, whether or not
		// they are deleted. This grows without bound, it's a major memory
		// leak.
		this(id, generator, true);
		this.file = generator.getFilename(id);
	}
	
	/**
	 * Constructor for the TempFileBucket object
	 * Subclasses can call this constructor.
	 * @param deleteOnExit Set if you want the bucket deleted on shutdown. Passed to 
	 * the parent BaseFileBucket. You must also override deleteOnExit() and 
	 * implement your own createShadow()!
	 * @param deleteOnFree True for a normal temp bucket, false for a shadow.
	 */
	protected TempFileBucket(
		long id,
		FilenameGenerator generator, boolean deleteOnFree) {
		super(generator.getFilename(id), false);
		this.filenameID = id;
		this.generator = generator;
		this.deleteOnFree = deleteOnFree;
		this.file = generator.getFilename(id);

            if (logDEBUG) {
                Logger.debug(this,"Initializing TempFileBucket(" + getFile());
            }
	}
	
	protected TempFileBucket() {
	    // For serialization.
	    deleteOnFree = false;
	}

	@Override
	protected boolean deleteOnFinalize() {
		// Make sure finalize wacks temp file 
		// if it is not explictly freed.
		return deleteOnFree; // not if shadow
	}
	
	@Override
	protected boolean createFileOnly() {
		return false;
	}

	@Override
	protected boolean deleteOnFree() {
		return deleteOnFree;
	}

	@Override
	public File getFile() {
	    if(file != null) return file;
		return generator.getFilename(filenameID);
	}

	@Override
	public boolean isReadOnly() {
		return readOnly;
	}

	@Override
	public void setReadOnly() {
		readOnly = true;
	}

	@Override
	protected boolean deleteOnExit() {
		// Temp files will be cleaned up on next restart.
		// File.deleteOnExit() is a hideous memory leak.
		// It should NOT be used for temp files.
		return false;
	}

	@Override
	public Bucket createShadow() {
		TempFileBucket ret = new TempFileBucket(filenameID, generator, false);
		ret.setReadOnly();
		if(!getFile().exists()) Logger.error(this, "File does not exist when creating shadow: "+getFile());
		return ret;
	}

    @Override
    public void onResume(ClientContext context) {
        if(persistent()) {
            generator = context.persistentFG;
            if(file == null) {
                // Migrating from old tempfile, possibly db4o era.
                file = generator.getFilename(filenameID);
                checkExists(file);
            } else {
                // File must exist!
                if(!file.exists()) {
                    // Maybe moved after the last checkpoint?
                    File f = generator.getFilename(filenameID);
                    if(f.exists()) {
                        file = f;
                        return;
                    }
                }
                checkExists(file);
                file = generator.maybeMove(file, filenameID);
            }
        } else {
            // Plain TempFileBucket's are not persistent.
            throw new UnsupportedOperationException();
        }
    }
    
    private void checkExists(File file) {
        // File must exist!
        try {
            if(!(file.createNewFile() || file.exists()))
                throw new IllegalStateException("Tempfile "+file+" does not exist and cannot be created");
        } catch (IOException e) {
            throw new IllegalStateException("Tempfile cannot be created");
        }
    }

    protected boolean persistent() {
        return false;
    }

    @Override
    protected boolean tempFileAlreadyExists() {
        return true;
    }
}
