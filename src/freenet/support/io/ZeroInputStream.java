package freenet.support.io;

import java.io.InputStream;
import java.util.Arrays;

@Deprecated
public class ZeroInputStream extends InputStream {
    
    @Override
    public int read() {
        return 0;
    }
    
    @Override
    public int read(byte[] buf) {
        return read(buf, 0, buf.length);
    }
    
    @Override
    public int read(byte[] buf, int offset, int length) {
        Arrays.fill(buf, offset, offset + length, (byte) 0);
        return length;
    }

}
