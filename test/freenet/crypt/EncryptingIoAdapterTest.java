package freenet.crypt;

import java.util.Arrays;

import junit.framework.TestCase;

import com.db4o.io.MemoryIoAdapter;

import freenet.support.math.MersenneTwister;

public class EncryptingIoAdapterTest extends TestCase {

	final MersenneTwister mt = new MersenneTwister(1881);

	public void testLinear() {
		for(int i=1;i<=1<<18;i*=2) {
			checkLinear(i, i, i);
		}
		for(int i=1024;i<=2048;) {
			checkLinear(i, i, i);
			checkLinear(i, 57, 57);
			i += mt.nextInt(5);
		}
		for(int i=1024;i<=1<<18;i*=2) {
			checkLinear(i, 1024, 1024);
		}
		for(int x=0;x<(1<<12);x++) {
			int size = 1<<11;
			int stepRead = mt.nextInt(1<<(mt.nextInt(5)+2))+1;
			checkLinear(size, stepRead, stepRead);
		}
		checkLinear(65536, 1024, 512);
		
		// Test case known to have failed at some point.
		checkLinearBuffered(1<<11, 71, 20, 152661464779838170L);
		// FIXME this next one doesn't work. Looks like a problem with MersenneTwister? TODO DEBUG!
		// Shows an extra byte in the ciphertext at the beginning of the second 71 byte segment.
		//checkLinear(1<<11, 71, 20, 152661464779838170L);
		
		for(int x=0;x<1<<6;x++) {
			int size = 1<<10;
			int stepRead = mt.nextInt(1<<(mt.nextInt(3)+4))+1;
			int stepWrite = mt.nextInt(1<<(mt.nextInt(3)+4))+1;
			checkLinear(size, stepWrite, stepRead);
		}
	}
	
	public void checkLinear(int size, int writeStep, int readStep) {
		long seed = mt.nextLong();
		checkLinearBuffered(size, writeStep, readStep, seed);
	}
	
	public void checkLinear(int size, int writeStep, int readStep, long seed) {
		EncryptingIoAdapter a = createAdapter();
		MersenneTwister dataMT = new MersenneTwister(seed);
		for(int written=0;written<size;) {
			int write = Math.min(writeStep, size-written);
			byte[] data = new byte[write];
			dataMT.nextBytes(data);
			a.write(data);
			written += write;
		}
		dataMT = new MersenneTwister(seed);
		a.seek(0);
		byte[] readBuffer = new byte[readStep];
		for(int read=0;read<size;) {
			int toRead = Math.min(readStep, size-read);
			int readBytes = a.read(readBuffer, toRead);
			assertTrue(readBytes > 0);
			byte[] compareBuf = new byte[readBytes];
			dataMT.nextBytes(compareBuf);
			for(int i=0;i<compareBuf.length;i++)
				assertTrue("Byte "+(i+read)+" is wrong for buffered test size="+size+" write="+writeStep+" read="+readStep+" seed="+seed, readBuffer[i] == compareBuf[i]);
			read += readBytes;
		}
		a.close();
	}

	public void checkLinearBuffered(int size, int writeStep, int readStep, long seed) {
		EncryptingIoAdapter a = createAdapter();
		MersenneTwister dataMT = new MersenneTwister(seed);
		byte[] bigBuffer = new byte[size];
		dataMT.nextBytes(bigBuffer);
		for(int written=0;written<size;) {
			int write = Math.min(writeStep, size-written);
			byte[] data = new byte[write];
			System.arraycopy(bigBuffer,written,data,0,write);
			a.write(data);
			written += write;
		}
		a.seek(0);
		byte[] readBuffer = new byte[readStep];
		for(int read=0;read<size;) {
			int toRead = Math.min(readStep, size-read);
			int readBytes = a.read(readBuffer, toRead);
			assertTrue("Read "+readBytes+" bytes at "+read+"/"+size+" toRead="+toRead+" readStep="+readStep,readBytes > 0);
			byte[] compareBuf = new byte[readBytes];
			System.arraycopy(bigBuffer,read,compareBuf,0,readBytes);
			for(int i=0;i<compareBuf.length;i++)
                                assertTrue("Byte "+(i+read)+" is wrong for buffered test size="+size+" write="+writeStep+" read="+readStep+" seed="+seed, readBuffer[i] == compareBuf[i]);               

			read += readBytes;
		}
		a.close();
	}

	private EncryptingIoAdapter createAdapter() {
		MemoryIoAdapter a = new MemoryIoAdapter();
		byte[] key = new byte[32];
		mt.nextBytes(key);
		EncryptingIoAdapter e = new EncryptingIoAdapter(a, key, new DummyRandomSource(mt.nextLong()));
		return (EncryptingIoAdapter) e.open("test", false, 1024, false);
	}
	
	public void testClobberBuffer() {
		EncryptingIoAdapter a = createAdapter();
		byte[] buf = new byte[4096];
		for(int i=0;i<buf.length;i++)
			buf[i] = (byte)i;
		byte[] copyBuf = new byte[buf.length];
		System.arraycopy(buf, 0, copyBuf, 0, buf.length);
		a.write(buf);
		assertTrue(Arrays.equals(buf, copyBuf));
	}
	
	public void testFlatRandom() {
		EncryptingIoAdapter a = createAdapter();
		int size = 32*1024;
		// Fill it up with a sequence predictable from the offset.
		// In this case it's 0123456789...
		byte[] buf = new byte[4096];
		for(int i=0;i<buf.length;i++)
			buf[i] = (byte)i;
		for(int written=0;written<size;written+=buf.length)
			a.write(buf);
		for(int i=0;i<1024;i++) {
			int start = mt.nextInt(size);
			int end = start == size - 1 ? size : (start + mt.nextInt(size-start));
			int length = end-start+1;
			byte[] check = new byte[length];
			a.seek(start);
			int read = 0;
			byte[] tmpBuf = new byte[4096];
			while(read < length) {
				int toRead = Math.min(tmpBuf.length, length-read);
				int readBytes = a.read(tmpBuf, toRead);
				assertTrue(readBytes > 0);
				System.arraycopy(tmpBuf, 0, check, read, readBytes);
				read += readBytes;
			}
			int offset = start;
			for(int j=0;j<check.length;j++) {
				assertTrue(check[j] == (byte)offset);
				offset++;
			}
		}
		a.close();
	}
	
	public void testMirrored() {
		checkMirrored(32*1024, true, 32);
		checkMirrored(256*1024, false, 256);
	}

	private void checkMirrored(int size, boolean slow, int rounds) {
		EncryptingIoAdapter a = createAdapter();
		byte[] fullBuf = new byte[size];
		byte[] checkFullBuf = slow ? new byte[size] : null;
		mt.nextBytes(fullBuf);
		a.write(fullBuf);
		for(int i=0;i<rounds;i++) {
			// Random write.
			int start = mt.nextInt(size);
			int end = start == size - 1 ? size : (start + mt.nextInt(size-start));
			int length = end-start+1;
			byte[] buf = new byte[length];
			mt.nextBytes(buf);
			a.seek(start);
			a.write(buf);
			System.arraycopy(buf, 0, fullBuf, start, length);
			if(slow) {
				// Check the whole lot.
				a.seek(0);
				a.read(checkFullBuf);
				assertTrue(Arrays.equals(fullBuf, checkFullBuf));
			}
			// Random read.
			start = mt.nextInt(size);
			end = start == size - 1 ? size : (start + mt.nextInt(size-start));
			length = end-start+1;
			buf = new byte[length];
			a.seek(start);
			a.read(buf);
			byte[] compare = new byte[length];
			System.arraycopy(fullBuf, start, compare, 0, length);
			assertTrue(Arrays.equals(buf, compare));
		}
	}

}
