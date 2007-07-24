package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/*
 *  This code is part of FProxy, an HTTP proxy server for Freenet.
 *  It is distributed under the GNU Public Licence (GPL) version 2.  See
 *  http://www.gnu.org/ for further details of the GPL.
 */
/**
 * Temporary file handling. TempFileBuckets start empty.
 *
 * @author     giannij
 */
public class TempFileBucket extends BaseFileBucket implements Bucket, SerializableToFieldSetBucket {
	long filenameID;
	final FilenameGenerator generator;
	private static boolean logDebug = true;
	private boolean readOnly;
	/**
	 * Constructor for the TempFileBucket object
	 *
	 * @param  f  File
	 */
	public TempFileBucket(
		long id,
		FilenameGenerator generator) {
		super(generator.getFilename(id));
		this.filenameID = id;
		this.generator = generator;
		synchronized(this) {
			logDebug = Logger.shouldLog(Logger.DEBUG, this);
		}

		//System.err.println("FProxyServlet.TempFileBucket -- created: " +
		//         f.getAbsolutePath());
		synchronized(this) {
			if (logDebug)
				Logger.debug(
					this,
					"Initializing TempFileBucket(" + getFile());
		}
	}

	protected boolean deleteOnFinalize() {
		// Make sure finalize wacks temp file 
		// if it is not explictly freed.
		return true;
	}
	
	/**
	 *  Release
	 *
	 * @return    Success
	 */
	public synchronized boolean release() {
		File file = getFile();
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Releasing bucket: "+file, new Exception("debug"));
		//System.err.println("FProxyServlet.TempFileBucket -- release: " +                      // file.getAbsolutePath());

		//System.err.println("CALL STACK: ");
		//(new Exception()).printStackTrace();

		// Force all open streams closed.
		// Windows won't let us delete the file unless we
		// do this.
		logDebug = Logger.shouldLog(Logger.DEBUG, this);
		if (logDebug)
			Logger.debug(this, "Releasing TempFileBucket " + file);
		closing = true;
		for (int i = 0; i < streams.size(); i++) {
			try {
				if (streams.elementAt(i) instanceof InputStream) {
					InputStream is = (InputStream) streams.elementAt(i);
					is.close();

					if (logDebug) {
						Logger.debug(
							this,
							"closed open InputStream !: "
								+ file.getAbsolutePath(),
							new Exception("debug"));
						if (is instanceof FileBucketInputStream) {
							Logger.debug(
								this,
								"Open InputStream created: ",
								((FileBucketInputStream) is).e);
						}
					}
				} else if (streams.elementAt(i) instanceof OutputStream) {
					OutputStream os = (OutputStream) (streams.elementAt(i));
					os.close();
					if (logDebug) {
						Logger.debug(
							this,
							"closed open OutputStream !: "
								+ file.getAbsolutePath(),
							new Exception("debug"));
//						if (os instanceof FileBucketOutputStream) {
//							Logger.debug(
//								this,
//								"Open OutputStream created: ",
//								((FileBucketOutputStream) os).e);
//						}

					}
				}
			} catch (IOException ioe) {
			}
		}
		if (logDebug)
			Logger.debug(this, "Closed streams for " + file);
		if (released) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this,
						"Already released file: " + file.getName());
			if (file.exists())
				throw new IllegalStateException(
					"already released file "
						+ file.getName()
						+ " BUT IT STILL EXISTS!");
			return true;
		}
		if (logDebug)
			Logger.debug(
				this,
				"Checked for released for " + file);
		released = true;
		if (file.exists()) {
			if (logDebug)
				Logger.debug(
					this,
					"Deleting bucket " + file.getName());
			if (!file.delete()) {
				Logger.error(
					this,
					"Delete failed on bucket " + file.getName() + "which existed" + (file.exists() ? " and still exists" : " but doesn't now"),
					new Exception());
				// Nonrecoverable; even though the user can't fix it it's still very serious
				return false;
			}
		}
		if (logDebug)
			Logger.debug(
				this,
				"release() returning true for " + file);
		return true;
	}

	/**
	 *  Gets the released attribute of the TempFileBucket object
	 *
	 * @return    The released value
	 */
	public final synchronized boolean isReleased() {
		return released;
	}

//	/**
//	 *  Finalize
//	 *
//	 * @exception  Throwable  Description of the Exception
//	 */
//	public void finalize() throws Throwable {
//		if (logDebug)
//			Logger.debug(this, "Finalizing TempFileBucket for " + file);
//		super.finalize();
//	}

	protected Vector streams = new Vector(0, 1); // TFB is a very common object, we need the space
	private boolean released;
	private boolean closing;

	protected synchronized void deleteFile() {
		if (logDebug)
			Logger.debug(this, "Deleting " + getFile());
		getFile().delete();
	}

	protected synchronized void resetLength() {
		if (logDebug)
			Logger.debug(this, "Resetting length for " + getFile());
	}

	protected final synchronized void getLengthSynchronized(long len) throws IOException {
		//       Core.logger.log(this, "getLengthSynchronized("+len+
		// 		      "); fakeLength = "+fakeLength, Logger.DEBUG);
		if(len <= 0) return;
		length += len;
	}

	public synchronized String toString(){
		return "TempFileBucket (File: '"+getFile().getAbsolutePath()+"', streams: "+streams.size();
	}

	public SimpleFieldSet toFieldSet() {
		return null; // Not persistent
	}

	protected boolean createFileOnly() {
		return false;
	}

	protected boolean deleteOnFree() {
		return true;
	}

	public File getFile() {
		return generator.getFilename(filenameID);
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly() {
		readOnly = true;
	}

	protected boolean deleteOnExit() {
		return true;
	}
}
