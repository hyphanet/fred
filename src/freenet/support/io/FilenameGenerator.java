package freenet.support.io;

import java.io.File;
import java.io.IOException;

import freenet.crypt.RandomSource;
import freenet.support.HexUtil;

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
			tmpDir = new File(System.getProperty("java.io.tmpdir"));
		else
			tmpDir = dir;
        if(!tmpDir.exists()) {
            tmpDir.mkdir();
		}
		if(!(tmpDir.isDirectory() && tmpDir.canRead() && tmpDir.canWrite()))
			throw new IOException("Not a directory or cannot read/write: "+tmpDir);
		
		if(wipeFiles) {
			File[] filenames = tmpDir.listFiles();
			if(filenames != null) {
				for(int i=0;i<filenames.length;i++) {
					File f = filenames[i];
					String name = f.getName();
					if((((File.separatorChar == '\\') && name.toLowerCase().startsWith(prefix.toLowerCase())) ||
							name.startsWith(prefix))) {
						f.delete();
					}
				}
			}
		}
	}

	public File makeRandomFilename() {
		byte[] randomFilename = new byte[8]; // should be plenty
		while(true) {
			random.nextBytes(randomFilename);
			String filename = prefix + HexUtil.bytesToHex(randomFilename);
			File ret = new File(tmpDir, filename);
			if(!ret.exists()) return ret;
		}
	}

}
