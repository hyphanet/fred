package freenet.support.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public abstract class BaseFileBucket implements Bucket, SerializableToFieldSetBucket {

	// JVM caches File.size() and there is no way to flush the cache, so we
	// need to track it ourselves
	protected long length;
	protected long fileRestartCounter;

	protected static String tempDir = null;

	public BaseFileBucket(File file) {
		this.length = file.length();
		if(deleteOnExit()) {
			try {
				file.deleteOnExit();
			} catch (NullPointerException e) {
				Logger.error(this, "Impossible: "+e, e);
				System.err.println("Impossible: "+e);
				e.printStackTrace();
			}
		}
	}

	public OutputStream getOutputStream() throws IOException {
		synchronized (this) {
			File file = getFile();
			if(isReadOnly())
				throw new IOException("Bucket is read-only: "+this);
			
			if(createFileOnly() && file.exists())
				throw new FileExistsException(file);
			
			// FIXME: behaviour depends on UNIX semantics, to totally abstract
			// it out we would have to kill the old write streams here
			// FIXME: what about existing streams? Will ones on append append
			// to the new truncated file? Do we want them to? What about
			// truncated ones? We should kill old streams here, right?
			return newFileBucketOutputStream(createFileOnly() ? getTempfile() : file, file.getPath(), ++fileRestartCounter);
		}
	}

	protected abstract boolean createFileOnly();
	
	protected abstract boolean deleteOnExit();
	
	protected abstract boolean deleteOnFinalize();
	
	protected abstract boolean deleteOnFree();

	/**
	 * Create a temporary file in the same directory as this file.
	 */
	protected File getTempfile() throws IOException {
		File file = getFile();
		File f = File.createTempFile(file.getName(), ".freenet-tmp", file.getParentFile());
		if(deleteOnExit()) f.deleteOnExit();
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
				Logger.minor(this, "Writing to "+tempfile+" for "+getFile());
			this.tempfile = tempfile;
			resetLength();
			this.restartCount = restartCount;
			closed = false;
		}
		
		protected void confirmWriteSynchronized() throws IOException {
			if (fileRestartCounter > restartCount)
				throw new IllegalStateException("writing to file after restart");
			if(isReadOnly())
				throw new IOException("File is read-only");
		}
		
		public void write(byte[] b) throws IOException {
			synchronized (BaseFileBucket.this) {
				confirmWriteSynchronized();
				super.write(b);
				length += b.length;
			}
		}

		public void write(byte[] b, int off, int len) throws IOException {
			synchronized (BaseFileBucket.this) {
				confirmWriteSynchronized();
				super.write(b, off, len);
				length += len;
			}
		}

		public void write(int b) throws IOException {
			synchronized (BaseFileBucket.this) {
				confirmWriteSynchronized();
				super.write(b);
				length++;
			}
		}
		
		public void close() throws IOException {
			File file;
			synchronized(this) {
				if(closed) return;
				closed = true;
				file = getFile();
			}
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR)
				Logger.minor(this, "Closing "+BaseFileBucket.this);
			try {
				super.close();
			} catch (IOException e) {
				if(logMINOR)
					Logger.minor(this, "Failed closing "+BaseFileBucket.this+" : "+e, e);
				if(createFileOnly()) tempfile.delete();
				throw e;
			}
			if(createFileOnly()) {
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
		File file = getFile();
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
		return getFile().getName();
	}

	public synchronized long size() {
		return length;
	}

	/**
	 * Actually delete the underlying file. Called by finalizer, will not be
	 * called twice. But length must still be valid when calling it.
	 */
	protected synchronized void deleteFile() {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Deleting "+getFile()+" for "+this, new Exception("debug"));
		getFile().delete();
	}

	public void finalize() {
		if(deleteOnFinalize())
			free(true);
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

	public synchronized Bucket[] split(int splitSize) {
		if(length > ((long)Integer.MAX_VALUE) * splitSize)
			throw new IllegalArgumentException("Way too big!: "+length+" for "+splitSize);
		int bucketCount = (int) (length / splitSize);
		if(length % splitSize > 0) bucketCount++;
		Bucket[] buckets = new Bucket[bucketCount];
		File file = getFile();
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
		File file = getFile();
		if ((deleteOnFree() || forceFree) && file.exists()) {
			Logger.debug(this,
				"Deleting bucket " + file.getName());
			deleteFile();
			if (file.exists())
				Logger.error(this,
					"Delete failed on bucket " + file.getName());
		}
	}
	
	public synchronized String toString() {
		return super.toString()+ ':' +getFile().getPath();
	}

	/**
	 * Returns the file object this buckets data is kept in.
	 */
	public abstract File getFile();
	
	public synchronized SimpleFieldSet toFieldSet() {
		if(deleteOnFinalize()) return null;
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.putSingle("Type", "FileBucket");
		fs.putSingle("Filename", getFile().toString());
		fs.put("Length", size());
		return fs;
	}

	public static Bucket create(SimpleFieldSet fs, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		String tmp = fs.get("Filename");
		if(tmp == null) throw new CannotCreateFromFieldSetException("No filename");
		File file = FileUtil.getCanonicalFile(new File(tmp));
		if(f.matches(file)) {
			return PersistentTempFileBucket.create(fs, f);
		}
		tmp = fs.get("Length");
		if(tmp == null) throw new CannotCreateFromFieldSetException("No length");
		try {
			long length = Long.parseLong(tmp);
			if(length !=  file.length())
				throw new CannotCreateFromFieldSetException("Invalid length: should be "+length+" actually "+file.length()+" on "+file);
		} catch (NumberFormatException e) {
			throw new CannotCreateFromFieldSetException("Corrupt length "+tmp, e);
		}
		FileBucket bucket = new FileBucket(file, false, true, false, false, false);
		if(file.exists()) // no point otherwise!
			f.register(file);
		return bucket;
	}

}
