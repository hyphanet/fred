package freenet.support.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Vector;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public abstract class BaseFileBucket implements Bucket {
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

	// JVM caches File.size() and there is no way to flush the cache, so we
	// need to track it ourselves
	protected long length;
	protected long fileRestartCounter;
	/** Has the bucket been freed? If so, no further operations may be done */
	private boolean freed;
	/** Vector of streams (FileBucketInputStream or FileBucketOutputStream) which 
	 * are open to this file. So we can be sure they are all closed when we free it. 
	 * Can be null. */
	private transient Vector<Object> streams;

	protected static String tempDir = null;

	/**
	 * Constructor.
	 * @param file
	 * @param deleteOnExit If true, call File.deleteOnExit() on the file. 
	 * WARNING: Delete on exit is a memory leak: The filenames are kept until the JVM exits, and 
	 * cannot be removed even when the file has been deleted! It should only be used where it is 
	 * ESSENTIAL! Note that if you want temp files to be deleted on exit, you also need to override
	 * deleteOnExit().
	 */
	public BaseFileBucket(File file, boolean deleteOnExit) {
		if(file == null) throw new NullPointerException();
		this.length = file.length();
                maybeSetDeleteOnExit(deleteOnExit, file);
	}

        private void maybeSetDeleteOnExit(boolean deleteOnExit, File file) {
        	if(deleteOnExit)
			setDeleteOnExit(file);
        }
	
	protected void setDeleteOnExit(File file) {
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

	@Override
	public OutputStream getOutputStream() throws IOException {
		synchronized (this) {
			File file = getFile();
			if(freed)
				throw new IOException("File already freed: "+this);
			if(isReadOnly())
				throw new IOException("Bucket is read-only: "+this);
			
			if(createFileOnly() && file.exists()) {
				boolean failed = true;
				if(fileRestartCounter > 0) {
					file.delete();
					if(!file.exists()) failed = false;
				}
				if(failed) throw new FileExistsException(file);
			}
			
			if(streams != null && !streams.isEmpty())
				Logger.error(this, "Streams open on "+this+" while opening an output stream!: "+streams, new Exception("debug"));
			
			File tempfile = createFileOnly() ? getTempfile() : file;
			long streamNumber = ++fileRestartCounter;
			
			FileBucketOutputStream os = 
				new FileBucketOutputStream(tempfile, streamNumber);
			
			if(logDEBUG)
				Logger.debug(this, "Creating "+os, new Exception("debug"));
			
			addStream(os);
			return os;
		}
	}

	private synchronized void addStream(Object stream) {
		// BaseFileBucket is a very common object, and often very long lived,
		// so we need to minimize memory usage even at the cost of frequent allocations.
		if(streams == null)
			streams = new Vector<Object>(1, 1);
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
			if(logMINOR)
				Logger.minor(FileBucketOutputStream.class, "Writing to "+tempfile+" for "+getFile()+" : "+this);
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
		
		@Override
		public void write(byte[] b) throws IOException {
			synchronized (BaseFileBucket.this) {
				confirmWriteSynchronized();
				super.write(b);
				length += b.length;
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			synchronized (BaseFileBucket.this) {
				confirmWriteSynchronized();
				super.write(b, off, len);
				length += len;
			}
		}

		@Override
		public void write(int b) throws IOException {
			synchronized (BaseFileBucket.this) {
				confirmWriteSynchronized();
				super.write(b);
				length++;
			}
		}
		
		@Override
		public void close() throws IOException {
			File file;
			synchronized(this) {
				if(closed) return;
				closed = true;
				file = getFile();
			}
			removeStream(this);
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
		
		@Override
		public String toString() {
			return super.toString()+":"+BaseFileBucket.this.toString();
		}
	}

	class FileBucketInputStream extends FileInputStream {
		boolean closed;

		public FileBucketInputStream(File f) throws IOException {
			super(f);
		}
		
		@Override
		public void close() throws IOException {
			synchronized(this) {
				if(closed) return;
				closed = true;
			}
			removeStream(this);
			super.close();
		}
		
		@Override
		public String toString() {
			return super.toString()+":"+BaseFileBucket.this.toString();
		}
	}

	@Override
	public synchronized InputStream getInputStream() throws IOException {
		if(freed)
			throw new IOException("File already freed: "+this);
		File file = getFile();
		if(!file.exists()) {
			Logger.normal(this, "File does not exist: "+file+" for "+this);
			return new NullInputStream();
		} else {
			FileBucketInputStream is =
				new FileBucketInputStream(file);
			addStream(is);
			if(logDEBUG)
				Logger.debug(this, "Creating "+is, new Exception("debug"));
			return is;
		}
	}

	/**
	 * @return the name of the file.
	 */
	@Override
	public synchronized String getName() {
		return getFile().getName();
	}

	@Override
	public synchronized long size() {
		return length;
	}

	/**
	 * Actually delete the underlying file. Called by finalizer, will not be
	 * called twice. But length must still be valid when calling it.
	 */
	protected synchronized void deleteFile() {
		if(logMINOR)
			Logger.minor(this, "Deleting "+getFile()+" for "+this, new Exception("debug"));
		getFile().delete();
	}

	@Override
	protected void finalize() throws Throwable {
		if(deleteOnFinalize())
			free(true);
                super.finalize();
	}

	/**
	 * Return directory used for temp files.
	 */
	public synchronized static String getTempDir() {
		return tempDir;  // **FIXME**/TODO: locking on tempDir needs to be checked by a Java guru for consistency
	}

	/**
	 * Set temp file directory.
	 * <p>
	 * The directory must exist.
	 */
	public synchronized static void setTempDir(String dirName) {
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
			long startAt = 1L * i * splitSize;
			long endAt = Math.min(startAt + splitSize * 1L, length);
			long len = endAt - startAt;
			buckets[i] = new ReadOnlyFileSliceBucket(file, startAt, len);
		}
		return buckets;
	}

	@Override
	public void free() {
		free(false);
	}
	
	public void free(boolean forceFree) {
		Object[] toClose;
		if(logMINOR)
			Logger.minor(this, "Freeing "+this, new Exception("debug"));
		synchronized(this) {
			if(freed) return;
			freed = true;
			toClose = streams == null ? null : streams.toArray();
			streams = null;
		}
		
		if(toClose != null) {
			Logger.error(this, "Streams open free()ing "+this+" : "+Arrays.toString(toClose), new Exception("debug"));
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
				"Deleting bucket " + file.getName(), new Exception("debug"));
			deleteFile();
			if (file.exists())
				Logger.error(this,
					"Delete failed on bucket " + file.getName());
		}
	}
	
	@Override
	public synchronized String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append(super.toString());
		sb.append(':');
		File f = getFile();
		if(f != null)
			sb.append(f.getPath());
		else
			sb.append("???");
		sb.append(":streams=");
		sb.append(streams == null ? 0 : streams.size());
		return sb.toString();
	}

	/**
	 * Returns the file object this buckets data is kept in.
	 */
	public abstract File getFile();
	
}
