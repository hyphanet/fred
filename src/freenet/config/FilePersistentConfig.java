/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.support.LogThresholdCallback;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.io.LineReadingInputStream;

/**
 * Global Config object which persists to a file.
 *
 * Reads the config file into a SimpleFieldSet when created.
 * During init, SubConfig's are registered, and fed the relevant parts of the SFS.
 * Once initialization has finished, we check whether there are any options remaining.
 * If so, we complain about them.
 * And then we write the config file back out.
 */
public class FilePersistentConfig extends PersistentConfig {

	final File filename;
	final File tempFilename;
	final protected String header;
	protected final Object storeSync = new Object();
	private boolean writeOnFinished;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public static FilePersistentConfig constructFilePersistentConfig(File f) throws IOException {
		return constructFilePersistentConfig(f, null);
	}

	public static FilePersistentConfig constructFilePersistentConfig(File f, String header) throws IOException {
		File filename = f;
		File tempFilename = new File(f.getPath()+".tmp");
		return new FilePersistentConfig(load(filename, tempFilename), filename, tempFilename, header);
	}

	static SimpleFieldSet load(File filename, File tempFilename) throws IOException {
		boolean filenameExists = filename.exists();
		boolean tempFilenameExists = tempFilename.exists();
		if(filenameExists && !filename.canWrite()) {
			Logger.error(FilePersistentConfig.class, "Warning: Cannot write to config file: "+filename);
			System.err.println("Warning: Cannot write to config file: "+filename);
		}
		if(tempFilenameExists && !tempFilename.canWrite()) {
			Logger.error(FilePersistentConfig.class, "Warning: Cannot write to config tempfile: "+tempFilename);
			System.err.println("Warning: Cannot write to config tempfile: "+tempFilename);
		}
		if(filenameExists) {
			if(filename.canRead() && filename.length() > 0) {
				try {
					return initialLoad(filename);
				} catch (FileNotFoundException e) {
					System.err.println("Cannot open config file "+filename+" : "+e+" - checking for temp file "+tempFilename);
				} catch (EOFException e) {
					System.err.println("Empty config file "+filename+" (end of file)");
				}
				// Other IOE's indicate a more serious problem.
			} else {
				// We probably won't be able to write it either.
				System.err.println("Cannot read config file "+filename);
			}
		}
		if(tempFilename.exists()) {
			if(tempFilename.canRead() && tempFilename.length() > 0) {
				try {
					return initialLoad(tempFilename);
				} catch (FileNotFoundException e) {
					System.err.println("Cannot open temp config file either: "+tempFilename+" : "+e);
				} // Other IOE's indicate a more serious problem.
			} else {
				System.err.println("Cannot read (temp) config file "+tempFilename);
				throw new IOException("Cannot read (temp) config file "+tempFilename);
			}
		}
		System.err.println("No config file found, creating new: "+filename);
		return null;
	}

	protected FilePersistentConfig(SimpleFieldSet origFS, File fnam, File temp) throws IOException {
		this(origFS, fnam, temp, null);
	}

	protected FilePersistentConfig(SimpleFieldSet origFS, File fnam, File temp, String header) throws IOException {
		super(origFS);
		this.filename = fnam;
		this.tempFilename = temp;
		this.header = header;
	}

	/** Load the config file into a SimpleFieldSet.
	 * @throws IOException */
	private static SimpleFieldSet initialLoad(File toRead) throws IOException {
		if(toRead == null) return null;
		FileInputStream fis = null;
		BufferedInputStream bis = null;
		LineReadingInputStream lis = null;
		try {
			fis = new FileInputStream(toRead);
			bis = new BufferedInputStream(fis);
			lis = new LineReadingInputStream(bis);
			// Config file is UTF-8 too!
			return new SimpleFieldSet(lis, 1024*1024, 128, true, true, true); // FIXME? advanced users may edit the config file, hence true?
		} finally {
			Closer.close(lis);
			Closer.close(bis);
			Closer.close(fis);
		}
	}

	@Override
	public void register(SubConfig sc) {
		super.register(sc);
	}

	@Override
	public void store() {
		if(!finishedInit) {
			writeOnFinished = true;
			return;
		}
		try {
			synchronized(storeSync) {
				innerStore();
			}
		} catch (IOException e) {
			String err = "Cannot store config: "+e;
			Logger.error(this, err, e);
			System.err.println(err);
			e.printStackTrace();
		}
	}

	/** Don't call without taking storeSync first */
	protected final void innerStore() throws IOException {
		if(!finishedInit)
			throw new IllegalStateException("SHOULD NOT HAPPEN!!");

		SimpleFieldSet fs = exportFieldSet();
		if(logMINOR)
			Logger.minor(this, "fs = " + fs);
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(tempFilename);
			synchronized(this) {
				fs.setHeader(header);
				fs.writeTo(fos);
			}
			fos.close();
			fos = null;
			FileUtil.renameTo(tempFilename, filename);
		}
		finally {
			Closer.close(fos);
		}
	}
	
	public void finishedInit() {
		super.finishedInit();
		if(writeOnFinished) {
			writeOnFinished = false;
			store();
		}
	}
}
