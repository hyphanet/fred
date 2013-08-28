package freenet.crypt;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Random;
import java.util.TreeSet;

class CorruptingOutputStream extends OutputStream {
    
    private final OutputStream os;
    /** Bytes to corrupt, in order */
    private final long[] killBytes;
    private int ptr;
    private long ctr;
    private final Random random;

    public CorruptingOutputStream(OutputStream os, long from, long to, int errors, Random random) {
        this.os = os;
        this.random = random;
        TreeSet<Long> toKill = new TreeSet<Long>();
        for(int i=0;i<errors;i++) {
            long offset = from + nextLong(random, to - from);
            if(!toKill.add(offset)) {
                i--;
                continue;
            }
        }
        killBytes = new long[errors];
        Iterator<Long> it = toKill.iterator();
        for(int i=0;i<errors;i++)
            killBytes[i] = it.next();
        ptr = 0;
    }
    
    public void write(int b) throws IOException {
        if(ptr < killBytes.length && ctr++ == killBytes[ptr]) {
            b ^= (1 << random.nextInt(7));
            ptr++;
        }
        os.write(b);
    }
    
    public void close() throws IOException {
        os.close();
    }
    
    public long nextLong(Random random, long range) {
        long maxFair = (Long.MAX_VALUE / range) * range;
        while(true) {
            long r = random.nextLong();
            if(r < 0) r = -r;
            if(r == Long.MIN_VALUE) continue; // Wierd case!
            if(r > maxFair) continue;
            return r % range;
        }
    }
    
}