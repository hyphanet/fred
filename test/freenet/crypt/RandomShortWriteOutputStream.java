package freenet.crypt;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;

public class RandomShortWriteOutputStream extends FilterOutputStream {
    
    private final Random random;
    
    public RandomShortWriteOutputStream(OutputStream os, Random random) {
        super(os);
        this.random = random;
    }
    
    @Override
    public void write(int b) throws IOException {
        out.write(b);
    }
    
    @Override
    public void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }
    
    @Override
    public void write(byte[] buf, int offset, int length) throws IOException {
        if(length > 2 && random.nextBoolean()) {
            int split = random.nextInt(length);
            out.write(buf, offset, split);
            out.write(buf, offset+split, length-split);
        } else {
            out.write(buf, offset, length);
        }
    }

}
