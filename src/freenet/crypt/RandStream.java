package freenet.crypt;
import freenet.Core;
import freenet.support.Logger;
import java.io.*;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Assuming you have an InputStream to a stream of random data,
 * this wraps that stream into the RandomSource interface
 *
 * @author Scott G. Miller
 */
public class RandStream extends RandomSource {
    private RandomSource fallBack;
    protected DataInputStream stream;

    //Avoid using this
    public RandStream() {}

    public RandStream(InputStream is) {
	this(is, new Yarrow());
    }

    public RandStream(InputStream is, RandomSource fallBack) {
	stream=new DataInputStream(is);
	this.fallBack=fallBack;
    }

        static final int bitTable[][]=
    { {0, 0x0}, 
      {1,  0x1}, {1,  0x3}, {1,  0x7}, {1,  0xf}, 
      {1, 0x1f}, {1, 0x3f}, {1, 0x7f}, {1, 0xff},
      
      {2,  0x1ff}, {2,  0x3ff}, {2,  0x7ff}, {2,  0xfff}, 
      {2, 0x1fff}, {2, 0x3fff}, {2, 0x7fff}, {2, 0xffff},

      {3,  0x1ffff}, {3,  0x3ffff}, {3,  0x7ffff}, {3,  0xfffff}, 
      {3, 0x1fffff}, {3, 0x3fffff}, {3, 0x7fffff}, {3, 0xffffff},

      {4,  0x1ffffff}, {4,  0x3ffffff}, {4,  0x7ffffff}, {4,  0xfffffff}, 
      {4, 0x1fffffff}, {4, 0x3fffffff}, {4, 0x7fffffff}, {4, 0xffffffff} };

    // This may *look* more complicated than in is, but in fact it is 
    // loop unrolled, cache and operation optimized.  
    // So don't try to simplify it... Thanks. :)
    protected int next(int bits) {
	int[] parameters=bitTable[bits];
	byte[] buffer=new byte[parameters[0]];
	try {
	    stream.readFully(buffer);
	} catch (IOException e) {}

	int val=buffer[0];

	if (parameters[0] == 4) 
	    val+= 
		(buffer[1]<<24) +
		(buffer[2]<<16) +
		(buffer[3]<< 8);
	else if (parameters[0] == 3) 
	    val+= 
		(buffer[1]<<16) +
		(buffer[2]<< 8);
	else if (parameters[0] == 2) 
	    val+=buffer[2]<<8;

	return val & parameters[1];
    }



    /**
     * Fills the array with bytes.length random bytes
     *
     * @param bytes array to fill with random bytes
     */
    public void nextBytes(byte[] bytes) {
	try {
	    stream.readFully(bytes);
	} catch (Exception c) {
	    Core.logger.log(this,"WARNING: Using fallback random source",Logger.NORMAL);
	    fallBack.nextBytes(bytes);
	}
    }

    /**
     * Returns a 32 bit random integer
     */
    public int nextInt() {
	try {
	    return stream.readInt();
	} catch (Exception c) {
	    Core.logger.log(this,"WARNING: Using fallback random source",Logger.NORMAL);
	    return fallBack.nextInt();
	}
    }


    /**
     * Returns a 64 bit random long
     */
    public long nextLong() {
	try {
	    return stream.readLong();
	} catch (Exception c) {
	    Core.logger.log(this,"WARNING: Using fallback random source",Logger.NORMAL);
	    return fallBack.nextLong();
	}
    }

    /**
     * Returns a 32 bit random floating point number
     */
    public float nextFloat() {
	try {
	    return stream.readFloat();
	} catch (Exception c) {
	    Core.logger.log(this,"WARNING: Using fallback random source",Logger.NORMAL);
	    return fallBack.nextFloat();
	}
    }

    /**
     * Returns a 64 bit random double
     */
    public double nextDouble() {
	try {
	    return stream.readDouble();
	} catch (Exception c) {
	    Core.logger.log(this,"WARNING: Using fallback random source",Logger.NORMAL);
	    return fallBack.nextDouble();
	}
    }

    public int acceptEntropy(EntropySource source, long data, int entropyBits) { return entropyBits; }
    public int acceptTimerEntropy(EntropySource timer) { return 32; }

    public void close() {
	try {
	    stream.close();
	} catch (Exception e) {}
    }
}


