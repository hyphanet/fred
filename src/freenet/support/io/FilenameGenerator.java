/*
  FilenameGenerator.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.support.io;

import java.io.File;
import java.io.IOException;

import freenet.crypt.RandomSource;
import freenet.support.HexUtil;
import freenet.support.Logger;

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
			if(!ret.exists()) {
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Made random filename: "+ret, new Exception("debug"));
				return ret;
			}
		}
	}

}
