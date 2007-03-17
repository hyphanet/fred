package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.Vector;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

public class FileBucketFactory implements BucketFactory {
    
    private Vector files = new Vector();
    
    // Must have trailing "/"
    public final File rootDir;

    public FileBucketFactory(File rootDir) {
        this.rootDir = rootDir;
    }

    public Bucket makeBucket(long size) throws IOException {
    	File f = File.createTempFile("bf_", ".freenet-tmp", rootDir);
        Bucket b = new FileBucket(f, false, true, true, false, true);
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
