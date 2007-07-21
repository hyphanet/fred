package freenet.support.io;

import java.io.IOException;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

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

	private static boolean logDebug=true;
	
	private final FilenameGenerator filenameGenerator;
	
	public static long defaultIncrement = 4096;
	
	public static float DEFAULT_FACTOR = 1.25F;

	// Storage accounting disabled by default.
	public TempBucketFactory(FilenameGenerator filenameGenerator) {
		logDebug = Logger.shouldLog(Logger.DEBUG,this);
		this.filenameGenerator = filenameGenerator;
	}

	public Bucket makeBucket(long size) throws IOException {
		return makeBucket(size, DEFAULT_FACTOR, defaultIncrement);
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
		long id = filenameGenerator.makeRandomFilename();

		return new TempFileBucket(id, filenameGenerator);
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
}
