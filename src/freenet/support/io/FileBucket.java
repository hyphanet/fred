/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;

import freenet.support.api.Bucket;

/**
 * A file Bucket is an implementation of Bucket that writes to a file.
 * 
 * @author oskar
 */
public class FileBucket extends BaseFileBucket implements Bucket, SerializableToFieldSetBucket {

	protected final File file;
	protected boolean readOnly;
	protected boolean deleteOnFinalize;
	protected boolean deleteOnFree;
	protected final boolean deleteOnExit;
	protected final boolean createFileOnly;
	// JVM caches File.size() and there is no way to flush the cache, so we
	// need to track it ourselves
	protected long fileRestartCounter;

	protected static String tempDir = null;

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
		super(file);
		if(file == null) throw new NullPointerException();
		file = file.getAbsoluteFile();
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
		if(!file.canRead())
			this.readOnly = true;
	}

	/**
	 * Returns the file object this buckets data is kept in.
	 */
	public synchronized File getFile() {
		return file;
	}

	public synchronized boolean isReadOnly() {
		return readOnly;
	}

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

	protected boolean createFileOnly() {
		return createFileOnly;
	}

	protected boolean deleteOnExit() {
		return deleteOnExit;
	}

	protected boolean deleteOnFinalize() {
		return deleteOnFinalize;
	}

	protected boolean deleteOnFree() {
		return deleteOnFree;
	}
}
