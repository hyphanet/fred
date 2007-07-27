package freenet.support.io;

import java.io.File;
import java.io.IOException;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.crypt.RandomSource;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.TimeUtil;

public class FilenameGenerator {

    private final RandomSource random;
    private final String prefix;
    private final File tmpDir;

    /**
     * @param random
     * @param wipeFiles
     * @param dir if <code>null</code> then use the default temporary directory
     * @param prefix
     * @throws IOException
     */
	public FilenameGenerator(RandomSource random, boolean wipeFiles, File dir, String prefix) throws IOException {
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
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Made random filename: "+ret, new Exception("debug"));
				return randomFilename;
			}
		}
	}

	public File getFilename(long id) {
		return new File(tmpDir, prefix + Long.toHexString(id));
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

}
