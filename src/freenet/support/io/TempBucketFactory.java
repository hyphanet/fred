package freenet.support.io;

import java.io.File;
import java.io.IOException;

import freenet.support.Logger;

/*
 * This code is part of FProxy, an HTTP proxy server for Freenet. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

/**
 * Temporary Bucket Factory
 * 
 * @author giannij
 */
public class TempBucketFactory implements BucketFactory {

	private static class NOPHook implements TempBucketHook {
		public void enlargeFile(long curLength, long finalLength) {
		}
		public void shrinkFile(long curLength, long finalLength) {
		}
		public void deleteFile(long curLength) {
		}
		public void createFile(long curLength) {
		}
	}

	private final static TempBucketHook DONT_HOOK = new NOPHook();
	private static TempBucketHook hook = DONT_HOOK;
	private static boolean logDebug=true;
	
	private final FilenameGenerator filenameGenerator;
	
	public static long defaultIncrement = 4096;

	// Storage accounting disabled by default.
	public TempBucketFactory(FilenameGenerator filenameGenerator) {
		logDebug = Logger.shouldLog(Logger.DEBUG,this);
		this.filenameGenerator = filenameGenerator;
	}

	public Bucket makeBucket(long size) throws IOException {
		return makeBucket(size, 1.25F, defaultIncrement);
	}

	public Bucket makeBucket(long size, float factor) throws IOException {
		return makeBucket(size, factor, defaultIncrement);
	}

	/**
	 * Create a temp bucket
	 * 
	 * @param size
	 *            Default size
	 * @param factor
	 *            Factor to increase size by when need more space
	 * @return A temporary Bucket
	 * @exception IOException
	 *                If it is not possible to create a temp bucket due to an
	 *                I/O error
	 */
	public TempFileBucket makeBucket(long size, float factor, long increment)
		throws IOException {
		logDebug = Logger.shouldLog(Logger.DEBUG,this);
		File f = filenameGenerator.makeRandomFilename();
		if(f == null) throw new NullPointerException();

		return new TempFileBucket(f, hook, size, increment, factor);
	}

	/**
	 * Free bucket
	 * 
	 * @param b
	 *            Description of the Parameter
	 */
	public void freeBucket(Bucket b) {
		if (b instanceof TempFileBucket) {
			if (logDebug)
				Logger.debug(
					this,
					"Temp bucket released: "
						+ ((TempFileBucket) b).getFile().getAbsolutePath(),
					new Exception("debug"));
			if (!((TempFileBucket) b).release()) {
				System.err.println("Could not release temp bucket" + b);
				Logger.error(
					this,
					"Could not release temp bucket " + b,
					 new Exception("Failed to release tempbucket"));
			}
		}
	}

	/**
	 * Sets the storage accounting hook.
	 * 
	 * @param t
	 *            The hook object to use to keep track of the amount of storage
	 *            used. It is legal for t to be null. In this case storage
	 *            accounting is disabled.
	 */
	public static void setHook(TempBucketHook t) {
		if (logDebug)
			Logger.debug(
				TempBucketFactory.class,
				"Set TempBucketHook to " + t);
		hook = t;
		if (hook == null) {
			// Allow hooks to be disabled w/o sprinkling
			// if (hook != null) {/*blah blah */} calls
			// throughout the code.
			hook = DONT_HOOK;
			if (logDebug) {
				Logger.debug(
					TempBucketHook.class,
					"TempBucketHook file usage management was disabled.");
			}
		}
	}
}
