package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Vector;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

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
