/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;

import com.db4o.ObjectContainer;

import freenet.support.Logger;
import freenet.support.api.Bucket;

/**
 * A file Bucket is an implementation of Bucket that writes to a file.
 * 
 * @author oskar
 */
public class FileBucket extends BaseFileBucket implements Bucket {

	protected final File file;
	protected boolean readOnly;
	protected boolean deleteOnFinalize;
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
	 * @param createFileOnly If true, create the file if it doesn't exist, but if it does exist,
	 * throw a FileExistsException on any write operation. This is safe against symlink attacks
	 * because we write to a temp file and then rename. It is technically possible that the rename
	 * will clobber an existing file if there is a race condition, but since it will not write over
	 * a symlink this is probably not dangerous. User-supplied filenames should in any case be
	 * restricted by higher levels.
	 * @param readOnly If true, any attempt to write to the bucket will result in an IOException.
	 * Can be set later. Irreversible. @see isReadOnly(), setReadOnly()
	 * @param deleteOnFinalize If true, delete the file on finalization. Reversible.
	 * @param deleteOnExit If true, delete the file on a clean exit of the JVM. Irreversible - use with care!
	 */
	public FileBucket(File file, boolean readOnly, boolean createFileOnly, boolean deleteOnFinalize, boolean deleteOnExit, boolean deleteOnFree) {
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
		this.deleteOnFinalize = deleteOnFinalize;
		this.deleteOnFree = deleteOnFree;
		this.deleteOnExit = deleteOnExit;
		// Useful for finding temp file leaks.
		// System.err.println("-- FileBucket.ctr(0) -- " +
		// file.getAbsolutePath());
		// (new Exception("get stack")).printStackTrace();
		fileRestartCounter = 0;
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

	/**
	 * Turn off "delete file on finalize" flag.
	 * Note that if you have already set delete file on exit, there is little that you
	 * can do to recover it! Delete file on finalize, on the other hand, is reversible.
	 */
	public synchronized void dontDeleteOnFinalize() {
		deleteOnFinalize = false;
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
	protected boolean deleteOnFinalize() {
		return deleteOnFinalize;
	}

	@Override
	protected boolean deleteOnFree() {
		return deleteOnFree;
	}

	@Override
	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.activate(file, 5);
		if(logMINOR) Logger.minor(this, "Removing "+this);
		container.delete(file);
		container.delete(this);
	}
	
	public void objectOnActivate(ObjectContainer container) {
		container.activate(file, 5);
	}
	
	// Debugging stuff. If reactivate, add the logging infrastructure and use if(logDEBUG).
//	public void objectOnNew(ObjectContainer container) {
//		Logger.minor(this, "Storing "+this, new Exception("debug"));
//	}
//	
//	public void objectOnUpdate(ObjectContainer container) {
//		Logger.minor(this, "Updating "+this, new Exception("debug"));
//	}
//	
//	public void objectOnDelete(ObjectContainer container) {
//		Logger.minor(this, "Deleting "+this, new Exception("debug"));
//	}
//	
	@Override
	public Bucket createShadow() {
		String fnam = file.getPath();
		File newFile = new File(fnam);
		return new FileBucket(newFile, true, false, false, false, false);
	}
}
