/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.crypt.RandomSource;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * A file Bucket is an implementation of Bucket that writes to a file.
 * 
 * @author oskar
 */
public class FileBucket implements Bucket, SerializableToFieldSetBucket {

	protected final File file;
	protected boolean readOnly;
	protected boolean deleteOnFinalize;
	protected boolean deleteOnFree;
	protected final boolean deleteOnExit;
	protected final boolean createFileOnly;
	protected long length;
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
		if(file == null) throw new NullPointerException();
		file = file.getAbsoluteFile();
		this.readOnly = readOnly;
		this.createFileOnly = createFileOnly;
		this.file = file;
		this.deleteOnFinalize = deleteOnFinalize;
		this.deleteOnFree = deleteOnFree;
		this.deleteOnExit = deleteOnExit;
		if(deleteOnExit) {
			try {
				file.deleteOnExit();
			} catch (NullPointerException e) {
				Logger.error(this, "Impossible: "+e, e);
				System.err.println("Impossible: "+e);
				e.printStackTrace();
			}
		}
		// Useful for finding temp file leaks.
		// System.err.println("-- FileBucket.ctr(0) -- " +
		// file.getAbsolutePath());
		// (new Exception("get stack")).printStackTrace();
		fileRestartCounter = 0;
		if(file.exists()) {
			length = file.length();
			if(!file.canWrite())
				readOnly = true;
		}
		else length = 0;
	}

	/**
	 * Creates a new FileBucket in a random temporary file in the temporary
	 * directory.
	 * @throws IOException 
	 */
	public FileBucket(RandomSource random) throws IOException {
		// **FIXME**/TODO: locking on tempDir needs to be checked by a Java guru for consistency
		file = File.createTempFile(tempDir, ".freenet.tmp");
		createFileOnly = true;
		// Useful for finding temp file leaks.
		//System.err.println("-- FileBucket.ctr(1) -- " +
		// file.getAbsolutePath());
		//(new Exception("get stack")).printStackTrace();
		deleteOnFinalize = true;
		deleteOnExit = true;
		length = 0;
		file.deleteOnExit();
	}

	public FileBucket(SimpleFieldSet fs, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		createFileOnly = true;
		deleteOnExit = false;
		String tmp = fs.get("Filename");
		if(tmp == null) throw new CannotCreateFromFieldSetException("No filename");
		this.file = FileUtil.getCanonicalFile(new File(tmp));
		tmp = fs.get("Length");
		if(tmp == null) throw new CannotCreateFromFieldSetException("No length");
		try {
			length = Long.parseLong(tmp);
			if(length !=  file.length())
				throw new CannotCreateFromFieldSetException("Invalid length: should be "+length+" actually "+file.length()+" on "+file);
		} catch (NumberFormatException e) {
			throw new CannotCreateFromFieldSetException("Corrupt length "+tmp, e);
		}
		if(file.exists()) // no point otherwise!
			f.register(file);
	}

	public OutputStream getOutputStream() throws IOException {
		synchronized (this) {
			if(readOnly)
				throw new IOException("Bucket is read-only");
			
			if(createFileOnly && file.exists())
				throw new FileExistsException(file);
			
			// FIXME: behaviour depends on UNIX semantics, to totally abstract
			// it out we would have to kill the old write streams here
			// FIXME: what about existing streams? Will ones on append append
			// to the new truncated file? Do we want them to? What about
			// truncated ones? We should kill old streams here, right?
			return newFileBucketOutputStream(createFileOnly ? getTempfile() : file, file.getPath(), ++fileRestartCounter);
		}
	}

	/**
	 * Create a temporary file in the same directory as this file.
	 */
	protected File getTempfile() throws IOException {
		File f = File.createTempFile(file.getName(), ".freenet-tmp", file.getParentFile());
		if(deleteOnExit) f.deleteOnExit();
		return f;
	}
	
	protected FileBucketOutputStream newFileBucketOutputStream(
		File tempfile, String s, long streamNumber) throws IOException {
		return new FileBucketOutputStream(tempfile, s, streamNumber);
	}

	protected synchronized void resetLength() {
		length = 0;
	}

	/**
	 * Internal OutputStream impl.
	 * If createFileOnly is set, we won't overwrite an existing file, and we write to a temp file
	 * then rename over the target. Note that we can't use createNewFile then new FOS() because while
	 * createNewFile is atomic, the combination is not, so if we do it we are vulnerable to symlink
	 * attacks.
	 * @author toad
	 */
	class FileBucketOutputStream extends FileOutputStream {

		private long restartCount;
		private File tempfile;
		private boolean closed;
		
		protected FileBucketOutputStream(
			File tempfile, String s, long restartCount)
			throws FileNotFoundException {
			super(tempfile, false);
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Writing to "+tempfile+" for "+file);
			this.tempfile = tempfile;
			resetLength();
			this.restartCount = restartCount;
			closed = false;
		}
		
		protected void confirmWriteSynchronized() throws IOException {
			if (fileRestartCounter > restartCount)
				throw new IllegalStateException("writing to file after restart");
			if(readOnly)
				throw new IOException("File is read-only");
		}
		
		public void write(byte[] b) throws IOException {
			synchronized (FileBucket.this) {
				confirmWriteSynchronized();
				super.write(b);
				length += b.length;
			}
		}

		public void write(byte[] b, int off, int len) throws IOException {
			synchronized (FileBucket.this) {
				confirmWriteSynchronized();
				super.write(b, off, len);
				length += len;
			}
		}

		public void write(int b) throws IOException {
			synchronized (FileBucket.this) {
				confirmWriteSynchronized();
				super.write(b);
				length++;
			}
		}
		
		public void close() throws IOException {
			synchronized(this) {
				if(closed) return;
				closed = true;
			}
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR)
				Logger.minor(this, "Closing "+FileBucket.this);
			try {
				super.close();
			} catch (IOException e) {
				if(logMINOR)
					Logger.minor(this, "Failed closing "+FileBucket.this+" : "+e, e);
				if(createFileOnly) tempfile.delete();
				throw e;
			}
			if(createFileOnly) {
				if(file.exists()) {
					if(logMINOR)
						Logger.minor(this, "File exists creating file for "+this);
					tempfile.delete();
					throw new FileExistsException(file);
				}
				if(!tempfile.renameTo(file)) {
					if(logMINOR)
						Logger.minor(this, "Cannot rename file for "+this);
					if(file.exists()) throw new FileExistsException(file);
					tempfile.delete();
					if(logMINOR)
						Logger.minor(this, "Deleted, cannot rename file for "+this);
					throw new IOException("Cannot rename file");
				}
			}
		}
	}

	class FileBucketInputStream extends FileInputStream {
		Exception e;

		public FileBucketInputStream(File f) throws IOException {
			super(f);
			if (Logger.shouldLog(Logger.DEBUG, this))
				e = new Exception("debug");
		}
	}

	public synchronized InputStream getInputStream() throws IOException {
		if(!file.exists()) {
			Logger.normal(this, "File does not exist: "+file+" for "+this);
			return new NullInputStream();
		} else 
			return new FileBucketInputStream(file);
	}

	/**
	 * @return the name of the file.
	 */
	public synchronized String getName() {
		return file.getName();
	}

	public synchronized long size() {
		return length;
	}

	/**
	 * Returns the file object this buckets data is kept in.
	 */
	public synchronized File getFile() {
		return file;
	}

	/**
	 * Actually delete the underlying file. Called by finalizer, will not be
	 * called twice. But length must still be valid when calling it.
	 */
	protected synchronized void deleteFile() {
		file.delete();
	}

	public void finalize() {
		if(deleteOnFinalize)
			free(deleteOnFinalize);
	}

	/**
	 * Return directory used for temp files.
	 */
	public final synchronized static String getTempDir() {
		return tempDir;  // **FIXME**/TODO: locking on tempDir needs to be checked by a Java guru for consistency
	}

	/**
	 * Set temp file directory.
	 * <p>
	 * The directory must exist.
	 */
	public final synchronized static void setTempDir(String dirName) {
		File dir = new File(dirName);
		if (!(dir.exists() && dir.isDirectory() && dir.canWrite())) {
			throw new IllegalArgumentException(
				"Bad Temp Directory: " + dir.getAbsolutePath());
		}
		tempDir = dirName;  // **FIXME**/TODO: locking on tempDir needs to be checked by a Java guru for consistency
	}

	// determine the temp directory in one of several ways

	static {
		// Try the Java property (1.2 and above)
		tempDir = System.getProperty("java.io.tmpdir");

		// Deprecated calls removed.

		// Try TEMP and TMP
		//	if (tempDir == null) {
		//	    tempDir = System.getenv("TEMP");
		//	}

		//	if (tempDir == null) {
		//	    tempDir = System.getenv("TMP");
		//	}

		// make some semi-educated guesses based on OS.

		if (tempDir == null) {
			String os = System.getProperty("os.name");
			if (os != null) {

				String[] candidates = null;

				// XXX: Add more possible OSes here.
				if (os.equalsIgnoreCase("Linux")
					|| os.equalsIgnoreCase("FreeBSD")) {
					String[] linuxCandidates = { "/tmp", "/var/tmp" };
					candidates = linuxCandidates;
				} else if (os.equalsIgnoreCase("Windows")) {
					String[] windowsCandidates =
						{ "C:\\TEMP", "C:\\WINDOWS\\TEMP" };
					candidates = windowsCandidates;
				}

				if (candidates != null) {
					for (int i = 0; i < candidates.length; i++) {
						File path = new File(candidates[i]);
						if (path.exists()
							&& path.isDirectory()
							&& path.canWrite()) {
							tempDir = candidates[i];
							break;
						}
					}
				}
			}
		}

		// last resort -- use current working directory

		if (tempDir == null) {
			// This can be null -- but that's OK, null => cwd for File
			// constructor, anyways.
			tempDir = System.getProperty("user.dir");
		}
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

	public synchronized Bucket[] split(int splitSize) {
		if(length > ((long)Integer.MAX_VALUE) * splitSize)
			throw new IllegalArgumentException("Way too big!: "+length+" for "+splitSize);
		int bucketCount = (int) (length / splitSize);
		if(length % splitSize > 0) bucketCount++;
		Bucket[] buckets = new Bucket[bucketCount];
		for(int i=0;i<buckets.length;i++) {
			long startAt = i * splitSize * 1L;
			long endAt = Math.min(startAt + splitSize * 1L, length);
			long len = endAt - startAt;
			buckets[i] = new ReadOnlyFileSliceBucket(file, startAt, len);
		}
		return buckets;
	}

	public void free() {
		free(false);
	}
	
	public synchronized void free(boolean forceFree) {
		if ((deleteOnFree || forceFree) && file.exists()) {
			Logger.debug(this,
				"Deleting bucket " + file.getName());
			deleteFile();
			if (file.exists())
				Logger.error(this,
					"Delete failed on bucket " + file.getName());
		}
	}
	
	public synchronized String toString() {
		return super.toString()+ ':' +file.getPath();
	}

	public synchronized SimpleFieldSet toFieldSet() {
		if(deleteOnFinalize) return null;
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.putSingle("Type", "FileBucket");
		fs.putSingle("Filename", file.toString());
		fs.put("Length", length);
		return fs;
	}
}
