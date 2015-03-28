/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.RandomAccessBucket;

/**
 * A file Bucket is an implementation of Bucket that writes to a file.
 * 
 * @author oskar
 */
public class FileBucket extends BaseFileBucket implements Bucket, Serializable {

    private static final long serialVersionUID = 1L;
    protected final File file;
	protected boolean readOnly;
	protected boolean deleteOnFree;
	protected final boolean deleteOnExit;
	protected final boolean createFileOnly;
	// JVM caches File.size() and there is no way to flush the cache, so we
	// need to track it ourselves
	
    private static volatile boolean logMINOR;

    static {
    	Logger.registerClass(FileBucket.class);
    }

	/**
	 * Creates a new FileBucket.
	 * 
	 * @param file The File to read and write to.
	 * @param readOnly If true, any attempt to write to the bucket will result in an IOException.
	 * Can be set later. Irreversible. @see isReadOnly(), setReadOnly()
	 * @param createFileOnly If true, create the file if it doesn't exist, but if it does exist,
	 * throw a FileExistsException on any write operation. This is safe against symlink attacks
	 * because we write to a temp file and then rename. It is technically possible that the rename
	 * will clobber an existing file if there is a race condition, but since it will not write over
	 * a symlink this is probably not dangerous. User-supplied filenames should in any case be
	 * restricted by higher levels.
	 * @param deleteOnExit If true, delete the file on a clean exit of the JVM. Irreversible - use with care!
	 * @param deleteOnFree If true, delete the file on finalization. Reversible.
	 */
	public FileBucket(File file, boolean readOnly, boolean createFileOnly, boolean deleteOnExit, boolean deleteOnFree) {
		super(file, deleteOnExit);
		if(file == null) throw new NullPointerException();
		File origFile = file;
		file = file.getAbsoluteFile();
		// Copy it so we can safely delete it.
		if(origFile == file)
			file = new File(file.getPath());
		this.readOnly = readOnly;
		this.createFileOnly = createFileOnly;
		this.file = file;
		this.deleteOnFree = deleteOnFree;
		this.deleteOnExit = deleteOnExit;
		// Useful for finding temp file leaks.
		// System.err.println("-- FileBucket.ctr(0) -- " +
		// file.getAbsolutePath());
		// (new Exception("get stack")).printStackTrace();
		fileRestartCounter = 0;
	}
	
	protected FileBucket() {
	    // For serialization.
	    super();
	    file = null;
	    deleteOnExit = false;
	    createFileOnly = false;
	}

    /**
	 * Returns the file object this buckets data is kept in.
	 */
	@Override
	public synchronized File getFile() {
		return file;
	}

	@Override
	public synchronized boolean isReadOnly() {
		return readOnly;
	}

	@Override
	public synchronized void setReadOnly() {
		readOnly = true;
	}

	@Override
	protected boolean createFileOnly() {
		return createFileOnly;
	}

	@Override
	protected boolean deleteOnExit() {
		return deleteOnExit;
	}

	@Override
	protected boolean deleteOnFree() {
		return deleteOnFree;
	}

	@Override
	public RandomAccessBucket createShadow() {
		String fnam = file.getPath();
		File newFile = new File(fnam);
		return new FileBucket(newFile, true, false, false, false);
	}

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        super.onResume(context);
    }

    @Override
    protected boolean tempFileAlreadyExists() {
        return false;
    }
    
    public static final int MAGIC = 0x8fe6e41b;
    static final int VERSION = 1;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        super.storeTo(dos);
        dos.writeInt(VERSION);
        dos.writeUTF(file.toString());
        dos.writeBoolean(readOnly);
        dos.writeBoolean(deleteOnFree);
        if(deleteOnExit) throw new IllegalStateException("Must not free on exit if persistent");
        dos.writeBoolean(createFileOnly);
    }
    
    protected FileBucket(DataInputStream dis) throws IOException, StorageFormatException {
        super(dis);
        int version = dis.readInt();
        if(version != VERSION) throw new StorageFormatException("Bad version");
        file = new File(dis.readUTF());
        readOnly = dis.readBoolean();
        deleteOnFree = dis.readBoolean();
        deleteOnExit = false;
        createFileOnly = dis.readBoolean();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (createFileOnly ? 1231 : 1237);
        result = prime * result + (deleteOnExit ? 1231 : 1237);
        result = prime * result + (deleteOnFree ? 1231 : 1237);
        result = prime * result + ((file == null) ? 0 : file.hashCode());
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
        FileBucket other = (FileBucket) obj;
        if (createFileOnly != other.createFileOnly) {
            return false;
        }
        if (deleteOnExit != other.deleteOnExit) {
            return false;
        }
        if (deleteOnFree != other.deleteOnFree) {
            return false;
        }
        if (file == null) {
            if (other.file != null) {
                return false;
            }
        } else if (!file.equals(other.file)) {
            return false;
        }
        if (readOnly != other.readOnly) {
            return false;
        }
        return true;
    }


}
