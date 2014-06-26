package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import freenet.support.Logger;

public class RandomAccessFileWrapper implements LockableRandomAccessThing {

	// FIXME maybe we should avoid opening these until we are ready to use them
	final RandomAccessFile raf;
	final File file;
	private boolean closed = false;
	private final long length;
	
	public RandomAccessFileWrapper(RandomAccessFile raf, File filename) throws IOException {
		this.raf = raf;
		this.file = filename;
		length = raf.length();
	}
	
	public RandomAccessFileWrapper(File filename, String mode) throws IOException {
		raf = new RandomAccessFile(filename, mode);
		length = raf.length();
		this.file = filename;
	}

    public RandomAccessFileWrapper(File filename, long length) throws IOException {
        raf = new RandomAccessFile(filename, "rw");
        raf.setLength(length);
        this.length = length;
        this.file = filename;
    }

	@Override
	public void pread(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
        // FIXME Use NIO (which has proper pread, with concurrency)! This is absurd!
		synchronized(this) {
			raf.seek(fileOffset);
			raf.readFully(buf, bufOffset, length);
		}
	}

	@Override
	public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
	    if(fileOffset + length > this.length)
	        throw new IOException("Length limit exceeded");
        // FIXME Use NIO (which has proper pwrite, with concurrency)! This is absurd!
		synchronized(this) {
			raf.seek(fileOffset);
			raf.write(buf, bufOffset, length);
		}
	}

	@Override
	public long size() throws IOException {
	    return length;
	}

	@Override
	public void close() {
		synchronized(this) {
			if(closed) return;
			closed = true;
		}
		try {
			raf.close();
		} catch (IOException e) {
			Logger.error(this, "Could not close "+raf+" : "+e+" for "+this, e);
		}
	}

    @Override
    public RAFLock lockOpen() {
        return new RAFLock() {

            @Override
            protected void innerUnlock() {
                // Do nothing. RAFW is always open.
            }
            
        };
    }

    @Override
    public void free() {
        close();
        try {
            FileUtil.secureDelete(file);
        } catch (IOException e) {
            Logger.error(this, "Unable to delete "+file+" : "+e, e);
            System.err.println("Unable to delete temporary file "+file);
        }
        //FIXME filename.delete(); ??
    }

}
