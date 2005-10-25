package freenet.support.io;
import java.io.*;

public class NullOutputStream extends OutputStream {
    public NullOutputStream() {}
    public void write(int b) {}
    public void write(byte[] buf, int off, int len) {}
}

