package freenet.support.io;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class DiskSpaceCheckingOutputStream extends FilterOutputStream {
    
    public DiskSpaceCheckingOutputStream(OutputStream out, DiskSpaceChecker checker, File file) {
        super(out);
        this.checker = checker;
        this.file = file;
    }
    
    private final File file;
    private final DiskSpaceChecker checker;
    private final int CHECK_EVERY = 4096;
    private long written;
    private long lastChecked;
    
    @Override
    public void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }
    
    @Override
    public void write(int i) throws IOException {
        write(new byte[] { (byte) i });
    }
    
    @Override
    public synchronized void write(byte[] buf, int offset, int length) throws IOException {
        if(written + length - lastChecked >= CHECK_EVERY) {
            if(!checker.checkDiskSpace(file, length, CHECK_EVERY))
                throw new InsufficientDiskSpaceException();
            lastChecked = written;
        }
        out.write(buf, offset, length);
        written += length;
    }

}
