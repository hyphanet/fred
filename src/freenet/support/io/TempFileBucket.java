package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.RandomAccessBucket;

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
	private transient boolean resumed;

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
	public RandomAccessBucket createShadow() {
		TempFileBucket ret = new TempFileBucket(filenameID, generator, false);
		ret.setReadOnly();
		if(!getFile().exists()) Logger.error(this, "File does not exist when creating shadow: "+getFile());
		return ret;
	}
	
	protected void innerResume(ClientContext context) throws ResumeFailedException {
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
	            }
	        }
	        checkExists(file);
	        file = generator.maybeMove(file, filenameID);
	    }
	}

    @Override
    public final void onResume(ClientContext context) throws ResumeFailedException {
        if(!persistent()) throw new UnsupportedOperationException();
        synchronized(this) {
            if(resumed) return;
            resumed = true;
        }
        super.onResume(context);
        innerResume(context);
    }
    
    private void checkExists(File file) throws ResumeFailedException {
        // File must exist!
        try {
            if(!(file.createNewFile() || file.exists()))
                throw new ResumeFailedException("Tempfile "+file+" does not exist and cannot be created");
        } catch (IOException e) {
            throw new ResumeFailedException("Tempfile cannot be created");
        }
    }

    protected boolean persistent() {
        return false;
    }

    @Override
    protected boolean tempFileAlreadyExists() {
        return true;
    }
    
    static final int VERSION = 1;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(magic());
        super.storeTo(dos);
        dos.writeInt(VERSION);
        dos.writeLong(filenameID);
        dos.writeBoolean(readOnly);
        dos.writeBoolean(deleteOnFree);
        dos.writeUTF(file.toString());
    }
    
    protected int magic() {
        throw new UnsupportedOperationException();
    }
    
    protected TempFileBucket(DataInputStream dis) throws IOException, StorageFormatException {
        super(dis);
        int version = dis.readInt();
        if(version != VERSION) throw new StorageFormatException("Bad version");
        filenameID = dis.readLong();
        if(filenameID == -1) throw new StorageFormatException("Bad filename ID");
        readOnly = dis.readBoolean();
        deleteOnFree = dis.readBoolean();
        file = new File(dis.readUTF());
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (deleteOnFree ? 1231 : 1237);
        result = prime * result + (int) (filenameID ^ (filenameID >>> 32));
        result = prime * result + (readOnly ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        TempFileBucket other = (TempFileBucket) obj;
        if (deleteOnFree != other.deleteOnFree) {
            return false;
        }
        if (filenameID != other.filenameID) {
            return false;
        }
        if (readOnly != other.readOnly) {
            return false;
        }
        return true;
    }

}
