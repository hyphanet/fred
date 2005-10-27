package freenet.support.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.crypt.RandomSource;
import freenet.support.Bucket;
import freenet.support.Logger;

/**
 * A file Bucket is an implementation of Bucket that writes to a file.
 * 
 * @author oskar
 */
public class FileBucket implements Bucket {

	protected File file;
	protected boolean readOnly;
	protected boolean newFile; // hack to get around deletes
	protected long length;
	// JVM caches File.size() and there is no way to flush the cache, so we
	// need to track it ourselves
	
	private int lastOutputStream;

	protected static String tempDir = null;

	/**
	 * Creates a new FileBucket.
	 * 
	 * @param file The File to read and write to.
	 * @param readOnly If true, any attempt to write to the bucket will result in an IOException.
	 * Can be set later. Irreversible. @see isReadOnly(), setReadOnly()
	 * @param deleteOnExit If true, delete the file on finalization.
	 *            
	 */
	public FileBucket(File file, boolean readOnly, boolean deleteOnExit) {
		this.readOnly = readOnly;
		this.file = file;
		this.newFile = deleteOnExit;
		if(newFile)
			file.deleteOnExit();
		// Useful for finding temp file leaks.
		// System.err.println("-- FileBucket.ctr(0) -- " +
		// file.getAbsolutePath());
		// (new Exception("get stack")).printStackTrace();
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
	 */

	public FileBucket(RandomSource random) {
		file =
			new File(
				tempDir,
				"t"
					+ Integer.toHexString(
						Math.abs(random.nextInt())));
		// Useful for finding temp file leaks.
		//System.err.println("-- FileBucket.ctr(1) -- " +
		// file.getAbsolutePath());
		//(new Exception("get stack")).printStackTrace();
		newFile = true;
		length = 0;
		file.deleteOnExit();
	}

	public OutputStream getOutputStream() throws IOException {
		synchronized (this) {
			if(readOnly)
				throw new IOException("Bucket is read-only");

			// FIXME: behaviour depends on UNIX semantics, to totally abstract
			// it out we would have to kill the old write streams here
			// FIXME: what about existing streams? Will ones on append append
			// to the new truncated file? Do we want them to? What about
			// truncated ones? We should kill old streams here, right?
			return newFileBucketOutputStream(file.getPath(), ++lastOutputStream);
		}
	}

	protected FileBucketOutputStream newFileBucketOutputStream(
		String s, int streamNumber) throws IOException {
		return new FileBucketOutputStream(s, streamNumber);
	}

	protected void resetLength() {
		length = 0;
	}

	class FileBucketOutputStream extends FileOutputStream {

		private int streamNumber;
		
		protected FileBucketOutputStream(
			String s, int streamNumber)
			throws FileNotFoundException {
			super(s, false);
			resetLength();
			this.streamNumber = streamNumber;
		}

		public void write(byte[] b) throws IOException {
			if(streamNumber != lastOutputStream)
				throw new IllegalStateException("Writing to old stream in "+getName());
			synchronized (FileBucket.this) {
				if(readOnly)
					throw new IOException("Bucket is read-only");
				super.write(b);
				length += b.length;
			}
		}

		public void write(byte[] b, int off, int len) throws IOException {
			if(streamNumber != lastOutputStream)
				throw new IllegalStateException("Writing to old stream in "+getName());
			synchronized (FileBucket.this) {
				if(readOnly)
					throw new IOException("Bucket is read-only");
				super.write(b, off, len);
				length += len;
			}
		}

		public void write(int b) throws IOException {
			if(streamNumber != lastOutputStream)
				throw new IllegalStateException("Writing to old stream in "+getName());
			synchronized (FileBucket.this) {
				if(readOnly)
					throw new IOException("Bucket is read-only");
				super.write(b);
				length++;
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

	public InputStream getInputStream() throws IOException {
		return file.exists()
			? (InputStream) new FileBucketInputStream(file)
			: (InputStream) new NullInputStream();
	}

	/**
	 * @return the name of the file.
	 */
	public String getName() {
		return file.getName();
	}

	public long size() {
		return length;
	}

	/**
	 * Returns the file object this buckets data is kept in.
	 */
	public File getFile() {
		return file;
	}

	/**
	 * Actually delete the underlying file. Called by finalizer, will not be
	 * called twice. But length must still be valid when calling it.
	 */
	protected void deleteFile() {
		file.delete();
	}

	public void finalize() {
		if (Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this,
				"FileBucket Finalizing " + file.getName());
		if (newFile && file.exists()) {
			Logger.debug(this,
				"Deleting bucket " + file.getName());
			deleteFile();
			if (file.exists())
				Logger.error(this,
					"Delete failed on bucket " + file.getName());
		}
		if (Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this,
				"FileBucket Finalized " + file.getName());
	}

	/**
	 * Return directory used for temp files.
	 */
	public final synchronized static String getTempDir() {
		return tempDir;
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
		tempDir = dirName;
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

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly() {
		readOnly = true;
	}
}
