package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class FilenameGenerator {

    private transient Random random;
    private String prefix;
    private File tmpDir;


    private static volatile boolean logMINOR;
    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
            }
        });
    }

    /**
     * @param random
     * @param wipeFiles
     * @param dir if <code>null</code> then use the default temporary directory
     * @param prefix
     * @throws IOException
     */
	public FilenameGenerator(Random random, boolean wipeFiles, File dir, String prefix) throws IOException {
		this.random = random;
		this.prefix = prefix;
		if (dir == null)
			tmpDir = FileUtil.getCanonicalFile(new File(System.getProperty("java.io.tmpdir")));
		else
			tmpDir = FileUtil.getCanonicalFile(dir);
        if(!tmpDir.exists()) {
            tmpDir.mkdir();
		}
		if(!(tmpDir.isDirectory() && tmpDir.canRead() && tmpDir.canWrite()))
			throw new IOException("Not a directory or cannot read/write: "+tmpDir);
		if(wipeFiles) {
			long wipedFiles = 0;
			long wipeableFiles = 0;
			long startWipe = System.currentTimeMillis();
			File[] filenames = tmpDir.listFiles();
			if(filenames != null) {
				for(int i=0;i<filenames.length;i++) {
					WrapperManager.signalStarting(5*60*1000);
					if(i % 1024 == 0 && i > 0)
						// User may want some feedback during startup
						System.err.println("Deleted "+wipedFiles+" temp files ("+(i - wipeableFiles)+" non-temp files in temp dir)");
					File f = filenames[i];
					String name = f.getName();
					if((((File.separatorChar == '\\') && name.toLowerCase().startsWith(prefix.toLowerCase())) ||
							name.startsWith(prefix))) {
						wipeableFiles++;
						if((!f.delete()) && f.exists())
							System.err.println("Unable to delete temporary file "+f+" - permissions problem?");
						else
							wipedFiles++;
					}
				}
				long endWipe = System.currentTimeMillis();
				System.err.println("Deleted "+wipedFiles+" of "+wipeableFiles+" temporary files ("+(filenames.length-wipeableFiles)+" non-temp files in temp directory) in "+TimeUtil.formatTime(endWipe-startWipe));
			}
		}
	}

	public long makeRandomFilename() {
		long randomFilename; // should be plenty
		while(true) {
			randomFilename = random.nextLong();
			if(randomFilename == -1) continue; // Disallowed as used for error reporting
			String filename = prefix + Long.toHexString(randomFilename);
			File ret = new File(tmpDir, filename);
			if(!ret.exists()) {
				if(logMINOR)
					Logger.minor(this, "Made random filename: "+ret, new Exception("debug"));
				return randomFilename;
			}
		}
	}

	public File getFilename(long id) {
		return new File(tmpDir, prefix + Long.toHexString(id));
	}
	
	public File makeRandomFile() throws IOException {
		while(true) {
			File file = getFilename(makeRandomFilename());
			if(file.createNewFile()) return file;
		}
	}

	public boolean matches(File file) {
		return getID(file) != -1;
	}

	public long getID(File file) {
		if(!(FileUtil.getCanonicalFile(file.getParentFile()).equals(tmpDir))) {
			Logger.error(this, "Not the same dir: parent="+FileUtil.getCanonicalFile(file.getParentFile())+" but tmpDir="+tmpDir);
			return -1;
		}
		String name = file.getName();
		if(!name.startsWith(prefix)) {
			Logger.error(this, "Does not start with prefix: "+name+" prefix "+prefix);
			return -1;
		}
		try {
			return Fields.hexToLong(name.substring(prefix.length()));
		} catch (NumberFormatException e) {
			Logger.error(this, "Cannot getID: "+e+" from "+(name.substring(prefix.length())), e);
			return -1;
		}
	}

	public File getDir() {
		return tmpDir;
	}

	/**
	 * Set up the dir and prefix. Note that while we can change the dir and prefix, we *cannot do so online*,
	 * at least not on Windows.
	 * @param dir
	 * @param prefix
	 */
	public void init(File dir, String prefix, Random random) throws IOException {
		this.random = random;
		// There is a problem with putting File's into db4o IIRC ... I think we workaround this somewhere else?
		// Symptoms are it trying to move even though the two dirs are blatantly identical.
		File oldDir = FileUtil.getCanonicalFile(new File(tmpDir.getPath()));
		File newDir = FileUtil.getCanonicalFile(dir);
		System.err.println("Old: "+oldDir+" prefix "+this.prefix+" from "+tmpDir+" old path "+tmpDir.getPath()+" old parent "+tmpDir.getParent());
		System.err.println("New: "+newDir+" prefix "+prefix+" from "+dir);
		if(newDir.exists() && newDir.isDirectory() && newDir.canWrite() && newDir.canRead() && !oldDir.exists()) {
			System.err.println("Assuming the user has moved the data from "+oldDir+" to "+newDir);
			tmpDir = newDir;
			return;
		}
		if(oldDir.equals(newDir) && this.prefix.equals(prefix)) {
			Logger.normal(this, "Initialised FilenameGenerator successfully - no change in dir and prefix: dir="+dir+" prefix="+prefix);
		} else if((!oldDir.equals(newDir)) && this.prefix.equals(prefix)) {
			if((!dir.exists()) && oldDir.renameTo(dir)) {
				tmpDir = dir;
				// This will interest the user, since they changed it.
				String msg = "Successfully renamed persistent temporary directory from "+tmpDir+" to "+dir;
				Logger.error(this, msg);
				System.err.println(msg);
			} else {
				if(!dir.exists()) {
					if((!dir.mkdir()) && !dir.exists()) {
						// FIXME localise these errors somehow??
						System.err.println("Unable to create new temporary directory: "+dir);
						throw new IOException("Unable to create new temporary directory: "+dir);
					}
				}
				if(!(dir.canRead() && dir.canWrite())) {
					// FIXME localise these errors somehow??
					System.err.println("Unable to read and write new temporary directory: "+dir);
					throw new IOException("Unable to read and write new temporary directory: "+dir);
				}
				int failed = 0;
				// Move each file
				for(File f: tmpDir.listFiles()) {
					String name = f.getName();
					if(!name.startsWith(prefix)) continue;
					if(!FileUtil.moveTo(f, new File(dir, name), true))
						failed++;
				}
				if(failed > 0) {
					// FIXME maybe a useralert
					System.err.println("WARNING: Not all files successfully moved changing temp dir: "+failed+" failed.");
					System.err.println("WARNING: Some persistent downloads etc may fail.");
				}
			}
		} else {
			if(!dir.exists()) {
				if((!dir.mkdir()) && (!dir.exists())) {
					// FIXME localise these errors somehow??
					System.err.println("Unable to create new temporary directory: "+dir);
					throw new IOException("Unable to create new temporary directory: "+dir);
				}
			}
			if(!(dir.canRead() && dir.canWrite())) {
				// FIXME localise these errors somehow??
				System.err.println("Unable to read and write new temporary directory: "+dir);
				throw new IOException("Unable to read and write new temporary directory: "+dir);
			}
			int failed = 0;
			// Move each file
			for(File f: tmpDir.listFiles()) {
				String name = f.getName();
				if(!name.startsWith(this.prefix)) continue;
				String newName = prefix + name.substring(this.prefix.length());
				if(!FileUtil.moveTo(f, new File(dir, newName), true)) {
					failed++;
				}
			}
			if(failed > 0) {
				// FIXME maybe a useralert
				System.err.println("WARNING: Not all files successfully moved changing temp dir: "+failed+" failed.");
				System.err.println("WARNING: Some persistent downloads etc may fail.");
			}
		}
	}

}
