package freenet.support.io;

import java.io.File;

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
	
	public SimpleFieldSet toFieldSet() {
		if(deleteOnFinalize())
			return null; // Not persistent
		// For subclasses i.e. PersistentTempFileBucket
		return super.toFieldSet();
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
