package freenet.support.io;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

import com.db4o.ObjectContainer;

import freenet.support.api.Bucket;

/** Pads a bucket to the next power of 2 file size. 
 * Note that self-terminating formats do not work with AEADCryptBucket; it needs to know the real 
 * length. This pads with FileUtil.fill(), which is reasonably random but is faster than using
 * SecureRandom, and vastly more secure than using a non-secure Random.
 */
public class TrivialPaddedBucket implements Bucket {
    
    private final Bucket underlying;
    private long size;
    private boolean outputStreamOpen;
    private boolean readOnly;

    /** Create a TrivialPaddedBucket, assumed to be empty */
    public TrivialPaddedBucket(Bucket underlying) {
        this(underlying, 0);
    }
    
    /** Create a TrivialPaddedBucket, specifying the actual size of the existing bucket, which we
     * do not store on disk.
     * @param underlying The underlying bucket.
     * @param size The actual size of the data.
     */
    public TrivialPaddedBucket(Bucket underlying, long size) {
        this.underlying = underlying;
        this.size = size;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        OutputStream os; 
        synchronized(this) {
            if(outputStreamOpen) throw new IOException("Already have an OutputStream for "+this);
            os = underlying.getOutputStream();
            outputStreamOpen = true;
            size = 0;
        }
        return new MyOutputStream(os);
    }
    
    private class MyOutputStream extends FilterOutputStream {
        
        MyOutputStream(OutputStream os) {
            super(os);
        }
        
        @Override
        public void write(int b) throws IOException {
            out.write(b);
            synchronized(TrivialPaddedBucket.this) {
                size++;
            }
        }
        
        @Override
        public void write(byte[] buf) throws IOException {
            out.write(buf);
            synchronized(TrivialPaddedBucket.this) {
                size += buf.length;
            }
        }
        
        @Override
        public void write(byte[] buf, int offset, int length) throws IOException {
            out.write(buf, offset, length);
            synchronized(TrivialPaddedBucket.this) {
                size += length;
            }
        }
        
        @Override
        public void close() throws IOException {
            try {
                long padding;
                synchronized(TrivialPaddedBucket.this) {
                    long paddedLength = paddedLength(size);
                    padding = paddedLength - size;
                }
                FileUtil.fill(out, padding);
                out.close();
            } finally {
                synchronized(TrivialPaddedBucket.this) {
                    outputStreamOpen = false;
                }
            }
        }
        
        public String toString() {
            return "TrivialPaddedBucketOutputStream:"+out+"("+TrivialPaddedBucket.this+")";
        }

    }
    
    private static final long MIN_PADDED_SIZE = 1024;
    
    private long paddedLength(long size) {
        if(size < MIN_PADDED_SIZE) size = MIN_PADDED_SIZE;
        if(size == MIN_PADDED_SIZE) return size;
        long min = MIN_PADDED_SIZE;
        long max = (long)MIN_PADDED_SIZE << 1;
        while(true) {
            if(max < 0)
                throw new Error("Impossible size: "+size+" - min="+min+", max="+max);
            if(size < min)
                throw new IllegalStateException("???");
            if((size >= min) && (size <= max)) {
                return max;
            }
            min = max;
            max = max << 1;
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new MyInputStream(underlying.getInputStream());
    }
    
    private class MyInputStream extends FilterInputStream {

        private long counter;
        
        public MyInputStream(InputStream is) {
            super(is);
        }
        
        @Override
        public int read() throws IOException {
            synchronized(TrivialPaddedBucket.this) {
                if(counter >= size) return -1;
            }
            int ret = in.read();
            synchronized(TrivialPaddedBucket.this) {
                counter++;
            }
            return ret;
        }
        
        @Override
        public int read(byte[] buf) throws IOException {
            return read(buf, 0, buf.length);
        }
        
        @Override
        public int read(byte[] buf, int offset, int length) throws IOException {
            synchronized(TrivialPaddedBucket.this) {
                if(length < 0) return -1;
                if(length == 0) return 0;
                if(counter >= size) return -1;
                if(counter + length >= size) {
                    length = (int)Math.min(length, size - counter);
                }
            }
            int ret = in.read(buf, offset, length);
            synchronized(TrivialPaddedBucket.this) {
                if(ret > 0)
                counter += ret;
            }
            return ret;
        }
        
        public long skip(long length) throws IOException {
            synchronized(TrivialPaddedBucket.this) {
                if(counter >= size) return -1;
                if(counter + length >= size) {
                    length = (int)Math.min(length, counter + length - size);
                }
            }
            long ret = in.skip(length);
            synchronized(TrivialPaddedBucket.this) {
                if(ret > 0) counter += ret;
            }
            return ret;
        }
        
        @Override
        public synchronized int available() throws IOException {
            long max = size - counter;
            int ret = in.available();
            if(max < ret) ret = (int)max;
            if(ret < 0) return 0;
            return ret;
        }
        
    }

    @Override
    public String getName() {
        return "Padded:"+underlying.getName();
    }

    @Override
    /** Get the size of the data written to the bucket (not the padded size). */
    public synchronized long size() {
        return size;
    }

    @Override
    public synchronized boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public synchronized void setReadOnly() {
        readOnly = true;
    }

    @Override
    public void free() {
        underlying.free();
    }

    @Override
    public void storeTo(ObjectContainer container) {
        underlying.storeTo(container);
        container.store(this);
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        underlying.removeFrom(container);
        container.delete(this);
    }
    
    @Override
    public Bucket createShadow() {
        Bucket shadow = underlying.createShadow();
        TrivialPaddedBucket ret = new TrivialPaddedBucket(shadow, size);
        ret.setReadOnly();
        return ret;
    }
    
}
