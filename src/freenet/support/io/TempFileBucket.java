package freenet.support.io;

import java.io.File;
import java.io.IOException;

import com.db4o.ObjectContainer;

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

	@Override
	protected boolean deleteOnFinalize() {
		// Make sure finalize wacks temp file 
		// if it is not explictly freed.
		return true;
	}
	
	@Override
	public SimpleFieldSet toFieldSet() {
		if(deleteOnFinalize())
			return null; // Not persistent
		// For subclasses i.e. PersistentTempFileBucket
		return super.toFieldSet();
	}

	@Override
	protected boolean createFileOnly() {
		return false;
	}

	@Override
	protected boolean deleteOnFree() {
		return true;
	}

	@Override
	public File getFile() {
		return generator.getFilename(filenameID);
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly() {
		readOnly = true;
	}

	@Override
	protected boolean deleteOnExit() {
		return true;
	}

	public void storeTo(ObjectContainer container) {
		container.store(generator);
		container.store(generator);
		container.store(this);
		container.store(this);
	}

	public void removeFrom(ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Removing from database: "+this);
		// filenameGenerator is a global, we don't need to worry about it.
		container.delete(this);
	}

	public Bucket createShadow() throws IOException {
		TempFileBucket ret = new TempFileBucket(filenameID, generator);
		ret.setReadOnly();
		return ret;
	}
}
