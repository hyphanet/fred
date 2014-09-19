package freenet.support.io;

import java.io.IOException;
import java.util.Random;

import freenet.support.api.RandomAccessBuffer;

import junit.framework.TestCase;

/** Base class for testing RandomAccessBuffer's. */
public abstract class RandomAccessBufferTestBase extends TestCase {
    
    /** Size list for small tests i.e. stuff that definitely fits in RAM */
    protected final int[] sizeList;
    /** Size list for big tests i.e. stuff that might not fit in RAM */
    private final long[] fullSizeList;
    
    protected RandomAccessBufferTestBase(int[] allSmallTests) {
        sizeList = allSmallTests;
        fullSizeList = new long[sizeList.length];
        for(int i=0;i<sizeList.length;i++) fullSizeList[i] = sizeList[i];
    }
    
    protected RandomAccessBufferTestBase(int[] smallTests, long[] bigTests) {
        sizeList = smallTests;
        fullSizeList = bigTests;
    }
    
    /** Construct an instance of a given size. 
     * @throws IOException */
    protected abstract RandomAccessBuffer construct(long size) throws IOException;
    
    private void innerTestSize(long sz) throws IOException {
        RandomAccessBuffer raf = construct(sz);
        assertEquals(raf.size(), sz);
        raf.close();
        raf.free();
    }

    /** Test that we can create and free a RandomAccessBuffer of various sizes, and it returns the correct
     * size. */
    public void testSize() throws IOException {
        for(long size : fullSizeList)
            innerTestSize(size);
    }
    
    private static final int BUFFER_SIZE = 65536;
    
    public void testFormula() throws IOException {
        Random r = new Random(2126);
        Formula modulo256 = new Formula() {

            @Override
            public byte getByte(long offset) {
                return (byte)offset;
            }
            
        };
        Formula modulo57 = new Formula() {

            @Override
            public byte getByte(long offset) {
                return (byte)(offset % 57);
            }
            
        };
        for(long size : fullSizeList) {
            innerTestFormula(size, r, modulo256);
            innerTestFormula(size, r, modulo57);
        }
    }
        
    protected interface Formula {
        byte getByte(long offset);
    }
    
    /** Write using a given formula in random small writes, then check using random small reads. */
    protected void innerTestFormula(long sz, Random r, Formula f) throws IOException {
        RandomAccessBuffer raf = construct(sz);
        assertEquals(raf.size(), sz);
        int x = 0;
        // Write (and check as go)
        while(x < sz) {
            int maxRead = (int)Math.min(BUFFER_SIZE, sz - x);
            int toRead = maxRead == 1 ? 1 : r.nextInt(maxRead-1)+1;
            byte[] buf = new byte[toRead];
            for(int i=0;i<buf.length;i++) buf[i] = f.getByte(i+x);
            raf.pwrite(x, buf, 0, toRead);
            for(int i=0;i<buf.length;i++) buf[i] = (byte)~buf[i];
            raf.pread(x, buf, 0, toRead);
            for(int i=0;i<buf.length;i++) assertEquals(buf[i], f.getByte(i+x));
            x += toRead;
        }
        // Read
        while(x < sz) {
            int maxRead = (int)Math.min(BUFFER_SIZE, sz - x);
            int toRead = r.nextInt(maxRead-1)+1;
            byte[] buf = new byte[toRead];
            raf.pread(x, buf, 0, toRead);
            for(int i=0;i<buf.length;i++) assertEquals(buf[i], f.getByte(i+x));
            x += toRead;
        }
        x = 0;
        raf.close();
        raf.free();
    }
    
    /** Test that we can't write or read after the size limit */
    public void testWriteOverLimit() throws IOException {
        Random r = new Random(21092506);
        innerTestWriteOverLimit(0L, 1);
        innerTestWriteOverLimit(1, 1);
        innerTestWriteOverLimit(1, 1024);
        for(int i=0;i<10;i++) {
            innerTestWriteOverLimit(1024*1024+1, r.nextInt(1024));
        }
        innerTestWriteOverLimit(1024*1024+1, 1024*1024);
        innerTestWriteOverLimit(1024*1024+1, 1024*1024+2);
        for(long size : fullSizeList) {
            innerTestWriteOverLimit(size, 1024);
        }
        for(int size : sizeList) {
            innerTestWriteOverLimit(size, size);
            innerTestWriteOverLimit(size, size+1);
        }
    }
    
    private void innerTestWriteOverLimit(long sz, int choppedBytes) throws IOException {
        RandomAccessBuffer raf = construct(sz);
        assertEquals(raf.size(), sz);
        long startAt = sz - choppedBytes;
        byte[] buf = new byte[choppedBytes];
        if(sz != 0 && choppedBytes < sz) {
            if(startAt >= 0)
                readWriteMustSucceed(raf, startAt, buf, 0, buf.length); // Read, write up to the end work.
            else
                try {
                    readWriteMustSucceed(raf, startAt, buf, 0, buf.length); // Read, write up to the end work.
                    fail("Should fail to read at negative index");
                } catch (IllegalArgumentException e) {
                    // Ok.
                }
        }
        if(startAt+1 >= 0)
            readWriteMustFail(raf, startAt+1, buf, 0, buf.length); // Read, write over the end fail.
        else
            try {
                readWriteMustSucceed(raf, startAt+1, buf, 0, buf.length); // Read, write up to the end work.
                fail("Should fail to read at negative index");
            } catch (IllegalArgumentException e) {
                // Ok.
            }
        readWriteMustFail(raf, sz, buf, 0, buf.length); // Read, write at the end fail.
        readWriteMustFail(raf, sz+1, buf, 0, buf.length); // One byte into end
        readWriteMustFail(raf, sz+1025, buf, 0, buf.length); // 1KB in
        readWriteMustFail(raf, sz+buf.length, buf, 0, buf.length);
        raf.close();
        raf.free();
    }

    private void readWriteMustSucceed(RandomAccessBuffer raf, long startAt, byte[] buf, int offset, int length) throws IOException {
        raf.pread(startAt, buf, 0, buf.length); // Should work
        raf.pwrite(startAt, buf, 0, buf.length); // Should work
    }

    private void readWriteMustFail(RandomAccessBuffer raf, long startAt, byte[] buf, int offset, int length) throws IOException {
        if(length == 0) return; // NOP.
        try {
            raf.pread(startAt, buf, 0, buf.length); // Should work
            fail("Must throw!");
        } catch (IOException e) {
            // Ok.
        }
        try {
            raf.pwrite(startAt, buf, 0, buf.length); // Should work
            fail("Must throw!");
        } catch (IOException e) {
            // Ok.
        }
    }

    public void testClose() throws IOException {
        // Try to cover any thresholds for e.g. moving to disk.
        // Implementations should add their own tests according to known thresholds (white box).
        for(long size : fullSizeList)
            innerTestClose(size);
    }
    
    /** Test that after closing a RandomAccessBuffer we cannot read from it or write to it */
    protected void innerTestClose(long sz) throws IOException {
        RandomAccessBuffer raf = construct(sz);
        raf.close();
        byte[] buf = new byte[(int)Math.min(1024, sz)];
        readWriteMustFail(raf, 0L, buf, 0, buf.length);
        raf.free();
    }
    
    public void testArray() throws IOException {
        Random r = new Random(21162506);
        for(int size : sizeList)
            innerTestArray(size, r, false);
    }
    
    /** Create an array, fill it with random numbers, write it sequentially to the 
     * RandomAccessBuffer, then read randomly and compare. */
    protected void innerTestArray(int len, Random r, boolean readOnly) throws IOException {
        if(len == 0) return;
        byte[] buf = new byte[len];
        r.nextBytes(buf);
        RandomAccessBuffer raf = construct(len);
        raf.pwrite(0L, buf, 0, buf.length);
        for(int i=0;i<100;i++) {
            int end = len == 1 ? 1 : r.nextInt(len)+1;
            int start = r.nextInt(end);
            checkArraySectionEqualsReadData(buf, raf, start, end, readOnly);
        }
        checkArraySectionEqualsReadData(buf, raf, 0, len, readOnly);
        if(len > 1)
            checkArraySectionEqualsReadData(buf, raf, 1, len-1, readOnly);
        raf.close();
        raf.free();
    }

    /** Check that the array section equals the read data, then write it and repeat the check. */
    public static void checkArraySectionEqualsReadData(byte[] buf, RandomAccessBuffer raf, int start, int end, boolean readOnly) throws IOException {
        int len = end - start;
        if(len == 0) return;
        byte[] tmp = new byte[len];
        raf.pread(start, tmp, 0, len);
        for(int i=0;i<len;i++)
            assertEquals(tmp[i], buf[start+i]);
        if(!readOnly) {
            raf.pwrite(start, buf, start, len);
        }
        for(int i=0;i<len;i++) tmp[i] = 0;
        raf.pread(start, tmp, 0, len);
        for(int i=0;i<len;i++)
            assertEquals(tmp[i], buf[start+i]);
    }

}
