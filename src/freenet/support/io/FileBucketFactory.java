/*
  FileBucketFactory.java / Freenet
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
import java.util.Vector;

import freenet.support.Logger;

public class FileBucketFactory implements BucketFactory {
    
    private int enumm = 0;
    private Vector files = new Vector();
    
    // Must have trailing "/"
    public String rootDir = "";

    public FileBucketFactory() {
        
    }

    public FileBucketFactory(String rootDir) {
        this.rootDir = (rootDir.endsWith(File.separator)
                        ? rootDir
                        : (rootDir + File.separator));
    }

    public FileBucketFactory(File dir) {
        this(dir.toString());
    }

    public Bucket makeBucket(long size) {
        File f;
        do {
            f = new File(rootDir + "bffile_" + ++enumm);
            // REDFLAG: remove hoaky debugging code
            // System.err.println("----------------------------------------");
            // Exception e = new Exception("created: " + f.getName());
            // e.printStackTrace();
            // System.err.println("----------------------------------------");
        } while (f.exists());
        Bucket b = new FileBucket(f, false, true, false, true);
        files.addElement(f);
        return b;
    }

    public void freeBucket(Bucket b) throws IOException {
        if (!(b instanceof FileBucket)) throw new IOException("not a FileBucket!");
        File f = ((FileBucket) b).getFile();
        //System.err.println("FREEING: " + f.getName());
        if (files.removeElement(f)) {
            if (!f.delete())
                Logger.error(this, "Delete failed on bucket "+f.getName(), new Exception());
	    files.trimToSize();
	}
    }
}
