package freenet.support.io;

import java.io.*;

public class CountedInputStream extends FilterInputStream {

    protected long count = 0;

    public CountedInputStream(InputStream in) {
        super(in);
	if(in == null) throw new IllegalStateException("null fed to CountedInputStream");
    }

    public final long count() {
        return count;
    }

    public int read() throws IOException {
        int ret = super.read();
        if (ret != -1)
            ++count;
        return ret;
    }

    public int read(byte[] buf, int off, int len) throws IOException {
        int ret = super.read(buf, off, len);
        if (ret != -1)
            count += ret;
        return ret;
    }
    
    public long skip(int n) throws IOException {
    	long l = in.skip(n);
    	if(l > 0) count += l;
    	return l;
    }
}

