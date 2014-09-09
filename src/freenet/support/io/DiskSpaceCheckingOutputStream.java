package freenet.support.io;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class DiskSpaceCheckingOutputStream extends FilterOutputStream {
    
    public DiskSpaceCheckingOutputStream(OutputStream out, DiskSpaceChecker checker, File file, int bufferSize) {
        super(out);
        this.checker = checker;
        this.file = file;
        this.bufferSize = bufferSize;
    }
    
    private final File file;
    private final DiskSpaceChecker checker;
    private final int bufferSize;
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
        if(written + length - lastChecked >= bufferSize) {
            if(!checker.checkDiskSpace(file, length, bufferSize))
                throw new InsufficientDiskSpaceException();
            lastChecked = written;
        }
        out.write(buf, offset, length);
        written += length;
    }

}
