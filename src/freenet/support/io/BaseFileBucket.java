package freenet.support.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.StringArray;
import freenet.support.api.Bucket;

public abstract class BaseFileBucket implements Bucket, SerializableToFieldSetBucket {

	// JVM caches File.size() and there is no way to flush the cache, so we
	// need to track it ourselves
	protected long length;
	protected long fileRestartCounter;
	/** Has the bucket been freed? If so, no further operations may be done */
	private boolean freed;
	/** Vector of streams (FileBucketInputStream or FileBucketOutputStream) which 
	 * are open to this file. So we can be sure they are all closed when we free it. 
	 * Can be null. */
	private Vector streams;

	protected static String tempDir = null;

	public BaseFileBucket(File file) {
		if(file == null) throw new NullPointerException();
		this.length = file.length();
		if(deleteOnExit()) {
			try {
				file.deleteOnExit();
			} catch (NullPointerException e) {
				if(WrapperManager.hasShutdownHookBeenTriggered()) {
					Logger.normal(this, "NullPointerException setting deleteOnExit while shutting down - buggy JVM code: "+e, e);
				} else {
					Logger.error(this, "Caught "+e+" doing deleteOnExit() for "+file+" - JVM bug ????");
				}
			}
		}
	}

	public OutputStream getOutputStream() throws IOException {
		synchronized (this) {
			File file = getFile();
			if(freed)
				throw new IOException("File already freed");
			if(isReadOnly())
				throw new IOException("Bucket is read-only: "+this);
			
			if(createFileOnly() && file.exists())
				throw new FileExistsException(file);
			
			if(streams != null && !streams.isEmpty())
				Logger.error(this, "Streams open on "+this+" while opening an output stream!: "+streams);
			
			File tempfile = createFileOnly() ? getTempfile() : file;
			long streamNumber = ++fileRestartCounter;
			
			FileBucketOutputStream os = 
				new FileBucketOutputStream(tempfile, streamNumber);
			
			addStream(os);
			return os;
		}
	}

	private synchronized void addStream(Object stream) {
		// BaseFileBucket is a very common object, and often very long lived,
		// so we need to minimize memory usage even at the cost of frequent allocations.
		if(streams == null)
			streams = new Vector(1,1);
		streams.add(stream);
	}
	
	private synchronized void removeStream(Object stream) {
		// Race condition is possible
		if(streams == null) return;
		streams.remove(stream);
		if(streams.isEmpty()) streams = null;
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
			File tempfile, long restartCount)
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
			synchronized(BaseFileBucket.this) {
				if (fileRestartCounter > restartCount)
					throw new IllegalStateException("writing to file after restart");
				if(freed)
					throw new IOException("writing to file after it has been freed");
			}
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
			removeStream(this);
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
		boolean closed;

		public FileBucketInputStream(File f) throws IOException {
			super(f);
			if (Logger.shouldLog(Logger.DEBUG, this))
				e = new Exception("debug");
		}
		
		public void close() throws IOException {
			synchronized(this) {
				if(closed) return;
				closed = true;
			}
			removeStream(this);
			super.close();
		}
	}

	public synchronized InputStream getInputStream() throws IOException {
		if(freed)
			throw new IOException("File already freed");
		File file = getFile();
		if(!file.exists()) {
			Logger.normal(this, "File does not exist: "+file+" for "+this);
			return new NullInputStream();
		} else {
			FileBucketInputStream is =
				new FileBucketInputStream(file);
			addStream(is);
			return is;
		}
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
	
	public void free(boolean forceFree) {
		Object[] toClose;
		synchronized(this) {
			if(freed) return;
			freed = true;
			toClose = streams == null ? null : streams.toArray();
			streams = null;
		}
		
		if(toClose != null) {
			Logger.error(this, "Streams open free()ing "+this+" : "+StringArray.toString(toClose), new Exception("debug"));
			for(int i=0;i<toClose.length;i++) {
				try {
					if(toClose[i] instanceof FileBucketOutputStream) {
						((FileBucketOutputStream) toClose[i]).close();
					} else {
						((FileBucketInputStream) toClose[i]).close();
					}
				} catch (IOException e) {
					Logger.error(this, "Caught closing stream in free(): "+e, e);
				} catch (Throwable t) {
					Logger.error(this, "Caught closing stream in free(): "+t, t);
				}
			}
		}
		
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
		return super.toString()+ ':' +getFile().getPath()+":streams="+(streams == null ? 0 : streams.size());
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
