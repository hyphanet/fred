package freenet.support.io;

import java.io.InputStream;

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
        for(int i=offset;i<offset+length;i++)
            buf[i] = 0;
        return length;
    }

}
