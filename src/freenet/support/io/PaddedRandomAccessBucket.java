package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.crypt.MasterSecret;
import freenet.support.api.LockableRandomAccessBuffer;
import freenet.support.api.RandomAccessBucket;

/** Pads a bucket to the next power of 2 file size. 
 * Note that self-terminating formats do not work with AEADCryptBucket; it needs to know the real 
 * length. This pads with FileUtil.fill(), which is reasonably random but is faster than using
 * SecureRandom, and vastly more secure than using a non-secure Random.
 */
public class PaddedRandomAccessBucket implements RandomAccessBucket, Serializable {
    
    private static final long serialVersionUID = 1L;
    private final RandomAccessBucket underlying;
    private long size;
    private transient boolean outputStreamOpen;
    private boolean readOnly;

    /** Create a PaddedBucket, assumed to be empty */
    public PaddedRandomAccessBucket(RandomAccessBucket underlying) {
        this(underlying, 0);
    }
    
    /** Create a PaddedBucket, specifying the actual size of the existing bucket, which we
     * do not store on disk.
     * @param underlying The underlying bucket.
     * @param size The actual size of the data.
     */
    public PaddedRandomAccessBucket(RandomAccessBucket underlying, long size) {
        this.underlying = underlying;
        this.size = size;
    }
    
    protected PaddedRandomAccessBucket() {
        // For serialization.
        underlying = null;
        size = 0;
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
    
    @Override
    public OutputStream getOutputStreamUnbuffered() throws IOException {
        OutputStream os; 
        synchronized(this) {
            if(outputStreamOpen) throw new IOException("Already have an OutputStream for "+this);
            os = underlying.getOutputStreamUnbuffered();
            outputStreamOpen = true;
            size = 0;
        }
        return new MyOutputStream(os);
    }
    
    private class MyOutputStream extends FilterOutputStream {
        
        private boolean closed;
        
        MyOutputStream(OutputStream os) {
            super(os);
        }
        
        @Override
        public void write(int b) throws IOException {
            out.write(b);
            synchronized(PaddedRandomAccessBucket.this) {
                size++;
            }
        }
        
        @Override
        public void write(byte[] buf) throws IOException {
            out.write(buf);
            synchronized(PaddedRandomAccessBucket.this) {
                if(closed) throw new IOException("Already closed");
                size += buf.length;
            }
        }
        
        @Override
        public void write(byte[] buf, int offset, int length) throws IOException {
            out.write(buf, offset, length);
            synchronized(PaddedRandomAccessBucket.this) {
                if(closed) throw new IOException("Already closed");
                size += length;
            }
        }
        
        @Override
        public void close() throws IOException {
            try {
                long padding;
                synchronized(PaddedRandomAccessBucket.this) {
                    if(closed) return;
                    closed = true;
                    long paddedLength = paddedLength(size);
                    padding = paddedLength - size;
                }
                FileUtil.fill(out, padding);
                out.close();
            } finally {
                synchronized(PaddedRandomAccessBucket.this) {
                    outputStreamOpen = false;
                }
            }
        }
        
        public String toString() {
            return "TrivialPaddedBucketOutputStream:"+out+"("+PaddedRandomAccessBucket.this+")";
        }

    }
    
    private static final long MIN_PADDED_SIZE = 1024;
    
    private long paddedLength(long size) {
        if(size < MIN_PADDED_SIZE) size = MIN_PADDED_SIZE;
        if(size == MIN_PADDED_SIZE) return size;
        long min = MIN_PADDED_SIZE;
        long max = MIN_PADDED_SIZE << 1;
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
    
    @Override
    public InputStream getInputStreamUnbuffered() throws IOException {
        return new MyInputStream(underlying.getInputStreamUnbuffered());
    }
    
    private class MyInputStream extends FilterInputStream {

        private long counter;
        
        public MyInputStream(InputStream is) {
            super(is);
        }
        
        @Override
        public int read() throws IOException {
            synchronized(PaddedRandomAccessBucket.this) {
                if(counter >= size) return -1;
            }
            int ret = in.read();
            synchronized(PaddedRandomAccessBucket.this) {
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
            synchronized(PaddedRandomAccessBucket.this) {
                if(length < 0) return -1;
                if(length == 0) return 0;
                if(counter >= size) return -1;
                if(counter + length >= size) {
                    length = (int)Math.min(length, size - counter);
                }
            }
            int ret = in.read(buf, offset, length);
            synchronized(PaddedRandomAccessBucket.this) {
                if(ret > 0)
                counter += ret;
            }
            return ret;
        }
        
        public long skip(long length) throws IOException {
            synchronized(PaddedRandomAccessBucket.this) {
                if(counter >= size) return -1;
                if(counter + length >= size) {
                    length = (int)Math.min(length, counter + length - size);
                }
            }
            long ret = in.skip(length);
            synchronized(PaddedRandomAccessBucket.this) {
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
    public RandomAccessBucket createShadow() {
        RandomAccessBucket shadow = underlying.createShadow();
        PaddedRandomAccessBucket ret = new PaddedRandomAccessBucket(shadow, size);
        ret.setReadOnly();
        return ret;
    }

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        underlying.onResume(context);
    }
    
    static final int MAGIC = 0x95c42e34;
    static final int VERSION = 1;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(VERSION);
        dos.writeLong(size);
        dos.writeBoolean(readOnly);
        underlying.storeTo(dos);
    }
    
    protected PaddedRandomAccessBucket(DataInputStream dis, FilenameGenerator fg, 
            PersistentFileTracker persistentFileTracker, MasterSecret masterKey) 
    throws IOException, StorageFormatException, ResumeFailedException {
        int version = dis.readInt();
        if(version != VERSION) throw new StorageFormatException("Bad version");
        size = dis.readLong();
        readOnly = dis.readBoolean();
        underlying = (RandomAccessBucket) BucketTools.restoreFrom(dis, fg, persistentFileTracker, masterKey);
    }

    @Override
    public LockableRandomAccessBuffer toRandomAccessBuffer() throws IOException {
        synchronized(this) {
            if(outputStreamOpen) throw new IOException("Must close first");
            readOnly = true;
        }
        underlying.setReadOnly();
        LockableRandomAccessBuffer u = underlying.toRandomAccessBuffer();
        return new PaddedRandomAccessBuffer(u, size);
    }

    public RandomAccessBucket getUnderlying() {
        return underlying;
    }
    
}
