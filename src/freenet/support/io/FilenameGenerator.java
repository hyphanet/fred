package freenet.support.io;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;

/** Tracks the current temporary files settings (dir and prefix), and translates between ID's and 
 * filenames. Also provides functions for creating tempfiles (which should be safe against symlink
 * attacks and race conditions). FIXME Consider using File.createTempFile(). Note that using our 
 * own code could actually be more secure if we use a better PRNG than they do (they use 
 * "new Random()" IIRC, but maybe that's fixed now?). If we do change to using 
 * File.createTempFile(), we will need to change TempFileBucket accordingly.
 * @author toad
 */
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
					WrapperManager.signalStarting((int) MINUTES.toMillis(5));
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

	public long makeRandomFilename() throws IOException {
		long randomFilename; // should be plenty
		while(true) {
			randomFilename = random.nextLong();
			if(randomFilename == -1) continue; // Disallowed as used for error reporting
			String filename = prefix + Long.toHexString(randomFilename);
			File ret = new File(tmpDir, filename);
			if(ret.createNewFile()) {
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
	    return getFilename(makeRandomFilename());
	}

	public File getDir() {
		return tmpDir;
	}

	protected boolean matches(File file) {
	    return FileUtil.equals(file.getParentFile(), tmpDir) && 
	        file.getName().startsWith(prefix);
	}

    public File maybeMove(File file, long id) {
        if(matches(file)) return file;
        File newFile = getFilename(id);
        Logger.normal(this, "Moving tempfile "+file+" to "+newFile);
        if(FileUtil.moveTo(file, newFile, false))
            return newFile;
        else {
            Logger.error(this, "Unable to move old temporary file "+file+" to "+newFile);
            return file;
        }
    }

}
