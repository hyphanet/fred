/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
/**<PRE>
 * SHA1.java - An implementation of the SHA-1 Algorithm
 * This version integrated into Freenet by Ian Clarke (02-02-2000) 
 * (i.clarke@dynamicblue.com) from a previous public domain version by 
 * Chuck McManis (cmcmanis@netcom.com) which was public domain
 * Tweaked by Mr.Tines<tines@windsong.demon.co.uk> for pegwit, June 1997
 * - added method 'frob()' which wipes the contents, so as to match
 * the bizarre behaviour of Pegwit's double barreled hashing.
 *
 * Based on the C code that Steve Reid wrote his header
 * was :
 *      SHA-1 in C
 *      By Steve Reid <steve@edmweb.com>
 *      100% Public Domain
 *
 *      Test Vectors (from FIPS PUB 180-1)
 *      "abc"
 *      A9993E36 4706816A BA3E2571 7850C26C 9CD0D89D
 *      "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
 *      84983E44 1C3BD26E BAAE4AA1 F95129E5 E54670F1
 *      A million repetitions of "a"
 *      34AA973C D4C4DAA4 F61EEB2B DBAD2731 6534016F
 </pre>*/
package freenet.crypt;
import java.util.Random;

import freenet.support.HexUtil;

/**
 * This is a simple port of Steve Reid's SHA-1 code into Java.
 * I've run his test vectors through the code and they all pass.
 */
public final class SHA1 implements Digest {

    private static boolean alwaysThisOne = false;
    
    public static Digest getInstance(boolean needProgressive) {
	if(alwaysThisOne) needProgressive = true;
	if(needProgressive) return new SHA1();
	else try {
	    return new JavaSHA1();
	} catch (java.security.NoSuchAlgorithmException e) {
	    alwaysThisOne = true;
	    return new SHA1();
	} catch (Exception e) {
	    return new SHA1();
	}
    }
    
    public static Digest getInstance() {
	return getInstance(false);
    }
    
    @Override
    public final int digestSize() {
        return 160;
    }
    
    
    private int state[] = new int[5];
    private long count;
    private byte[] digestBits;
    private boolean NSA = true;

    /**
     * Retrieves the value of a hash.
     * @param digest int[] into which to place 5 elements
     * @param offset index of first of the 5 elements
     */
    @Override
    public final void extract(int[] digest, int offset) {
        for(int i=0; i<5; ++i) {
            digest[i+offset] = 
                ((digestBits[4*i+0]<<24) & 0xFF000000) | 
                ((digestBits[4*i+1]<<16) & 0x00FF0000) |
                ((digestBits[4*i+2]<< 8) & 0x0000FF00) | 
                ((digestBits[4*i+3]    ) & 0x000000FF);
        }
    }

    /**
     * Variant constructor
     * @param b true for SHA-1, false for the original SHA-0
     */
    public SHA1(boolean b) {
        count = 0;
        NSA = b;
        init();
    }
    
    /**
     * Simple constructor for SHA-1
     */
    public SHA1() {
        this(true);
    }

    /*
     * The following array forms the basis for the transform
     * buffer. Update puts bytes into this buffer and then
     * transform adds it into the state of the digest.
     */
    private int block[] = new int[16];
    private int blockIndex;

    /*
     * These functions are taken out of #defines in Steve's
     * code. Java doesn't have a preprocessor so the first
     * step is to just promote them to real methods.
     * Later we can optimize them out into inline code,
     * note that by making them final some compilers will
     * inline them when given the -O flag.
     */
    private int rol(int value, int bits) {
        int q = (value << bits) | (value >>> (32 - bits));
        return q;
    }

    private int blk0(int i) {
        block[i] = (rol(block[i],24)&0xFF00FF00) |
         (rol(block[i],8)&0x00FF00FF);
        return block[i];
    }

    private int blk(int i) {
        block[i&15] = block[(i+13)&15]^block[(i+8)&15]^
                          block[(i+2)&15]^block[i&15];
        if(NSA)
        {   // this makes it SHA-1
            block[i&15] = rol(block[i&15], 1);
        }
        return (block[i&15]);
    }

    private void R0(int data[], int v, int w, int x , int y, int z, int i) {
        data[z] += ((data[w] & (data[x] ^ data[y] )) ^ data[y]) +
                                blk0(i) + 0x5A827999 + rol(data[v] ,5);
        data[w] = rol(data[w], 30);
    }

    private void R1(int data[], int v, int w, int x, int y, int z, int i) {
        data[z] += ((data[w] & (data[x] ^ data[y])) ^ data[y]) +
                                blk(i) + 0x5A827999 + rol(data[v] ,5);
        data[w] = rol(data[w], 30);
    }

    private void R2(int data[], int v, int w, int x, int y, int z, int i) {
        data[z] += (data[w] ^ data[x] ^ data[y]) +
                                blk(i) + 0x6ED9EBA1 + rol(data[v] ,5);
        data[w] = rol(data[w], 30);
    }

    private void R3(int data[], int v, int w, int x, int y, int z, int i) {
        data[z] += (((data[w] | data[x]) & data[y]) | (data[w] & data[x])) +
                                blk(i) + 0x8F1BBCDC + rol(data[v] ,5);
        data[w] = rol(data[w], 30);
    }

    private void R4(int data[], int v, int w, int x, int y, int z, int i) {
        data[z] += (data[w] ^ data[x] ^ data[y]) +
                                blk(i) + 0xCA62C1D6 + rol(data[v] ,5);
        data[w] = rol(data[w], 30);
    }


    /*
     * Steve's original code and comments :
     *
     * blk0() and blk() perform the initial expand.
     * I got the idea of expanding during the round function from SSLeay
     *
     * #define blk0(i) block->l[i]
     * #define blk(i) (block->l[i&15] = rol(block->l[(i+13)&15]^block->l[(i+8)&15] \
     *   ^block->l[(i+2)&15]^block->l[i&15],1))
     *
     * (R0+R1), R2, R3, R4 are the different operations used in SHA1
     * #define R0(v,w,x,y,z,i) z+=((w&(x^y))^y)+blk0(i)+0x5A827999+rol(v,5);w=rol(w,30);
     * #define R1(v,w,x,y,z,i) z+=((w&(x^y))^y)+blk(i)+0x5A827999+rol(v,5);w=rol(w,30);
     * #define R2(v,w,x,y,z,i) z+=(w^x^y)+blk(i)+0x6ED9EBA1+rol(v,5);w=rol(w,30);
     * #define R3(v,w,x,y,z,i) z+=(((w|x)&y)|(w&x))+blk(i)+0x8F1BBCDC+rol(v,5);w=rol(w,30);
     * #define R4(v,w,x,y,z,i) z+=(w^x^y)+blk(i)+0xCA62C1D6+rol(v,5);w=rol(w,30);
     */

    private int dd[] = new int[5];

    /**
     * Hash a single 512-bit block. This is the core of the algorithm.
     *
     * Note that working with arrays is very inefficent in Java as it
     * does a class cast check each time you store into the array.
     *
     */

    private void transform() {

        /* Copy context->state[] to working vars */
        dd[0] = state[0];
        dd[1] = state[1];
        dd[2] = state[2];
        dd[3] = state[3];
        dd[4] = state[4];
        /* 4 rounds of 20 operations each. Loop unrolled. */
        R0(dd,0,1,2,3,4, 0); R0(dd,4,0,1,2,3, 1); R0(dd,3,4,0,1,2, 2); R0(dd,2,3,4,0,1, 3);
        R0(dd,1,2,3,4,0, 4); R0(dd,0,1,2,3,4, 5); R0(dd,4,0,1,2,3, 6); R0(dd,3,4,0,1,2, 7);
        R0(dd,2,3,4,0,1, 8); R0(dd,1,2,3,4,0, 9); R0(dd,0,1,2,3,4,10); R0(dd,4,0,1,2,3,11);
        R0(dd,3,4,0,1,2,12); R0(dd,2,3,4,0,1,13); R0(dd,1,2,3,4,0,14); R0(dd,0,1,2,3,4,15);
        R1(dd,4,0,1,2,3,16); R1(dd,3,4,0,1,2,17); R1(dd,2,3,4,0,1,18); R1(dd,1,2,3,4,0,19);
        R2(dd,0,1,2,3,4,20); R2(dd,4,0,1,2,3,21); R2(dd,3,4,0,1,2,22); R2(dd,2,3,4,0,1,23);
        R2(dd,1,2,3,4,0,24); R2(dd,0,1,2,3,4,25); R2(dd,4,0,1,2,3,26); R2(dd,3,4,0,1,2,27);
        R2(dd,2,3,4,0,1,28); R2(dd,1,2,3,4,0,29); R2(dd,0,1,2,3,4,30); R2(dd,4,0,1,2,3,31);
        R2(dd,3,4,0,1,2,32); R2(dd,2,3,4,0,1,33); R2(dd,1,2,3,4,0,34); R2(dd,0,1,2,3,4,35);
        R2(dd,4,0,1,2,3,36); R2(dd,3,4,0,1,2,37); R2(dd,2,3,4,0,1,38); R2(dd,1,2,3,4,0,39);
        R3(dd,0,1,2,3,4,40); R3(dd,4,0,1,2,3,41); R3(dd,3,4,0,1,2,42); R3(dd,2,3,4,0,1,43);
        R3(dd,1,2,3,4,0,44); R3(dd,0,1,2,3,4,45); R3(dd,4,0,1,2,3,46); R3(dd,3,4,0,1,2,47);
        R3(dd,2,3,4,0,1,48); R3(dd,1,2,3,4,0,49); R3(dd,0,1,2,3,4,50); R3(dd,4,0,1,2,3,51);
        R3(dd,3,4,0,1,2,52); R3(dd,2,3,4,0,1,53); R3(dd,1,2,3,4,0,54); R3(dd,0,1,2,3,4,55);
        R3(dd,4,0,1,2,3,56); R3(dd,3,4,0,1,2,57); R3(dd,2,3,4,0,1,58); R3(dd,1,2,3,4,0,59);
        R4(dd,0,1,2,3,4,60); R4(dd,4,0,1,2,3,61); R4(dd,3,4,0,1,2,62); R4(dd,2,3,4,0,1,63);
        R4(dd,1,2,3,4,0,64); R4(dd,0,1,2,3,4,65); R4(dd,4,0,1,2,3,66); R4(dd,3,4,0,1,2,67);
        R4(dd,2,3,4,0,1,68); R4(dd,1,2,3,4,0,69); R4(dd,0,1,2,3,4,70); R4(dd,4,0,1,2,3,71);
        R4(dd,3,4,0,1,2,72); R4(dd,2,3,4,0,1,73); R4(dd,1,2,3,4,0,74); R4(dd,0,1,2,3,4,75);
        R4(dd,4,0,1,2,3,76); R4(dd,3,4,0,1,2,77); R4(dd,2,3,4,0,1,78); R4(dd,1,2,3,4,0,79);
        /* Add the working vars back into context.state[] */
        state[0] += dd[0];
        state[1] += dd[1];
        state[2] += dd[2];
        state[3] += dd[3];
        state[4] += dd[4];
    }
    
    /**
     * zero the count and state arrays; used to support
     * Pegwit's anomalous 2-barrel hashing
     */
    public final void frob() { // Pegwit's little anomaly
        count=0;
        state[0] = state[1] = state[2] = state[3] = state[4] = 0;
    }

    /**
     *
     * Initializes new context
     */
    protected final void init() {
        /* SHA1 initialization constants */
        state[0] = 0x67452301;
        state[1] = 0xEFCDAB89;
        state[2] = 0x98BADCFE;
        state[3] = 0x10325476;
        state[4] = 0xC3D2E1F0;
        count = 0;
        digestBits = new byte[20];
        blockIndex = 0;
    }

    /**
     * Add one byte to the digest. When this is implemented
     * all of the abstract class methods end up calling
     * this method for types other than bytes.
     * @param b byte to add
     */
    @Override
    public final void update(byte b) {
        int mask = (blockIndex & 3) << 3;
        count += 8;
        block[blockIndex >> 2] &= ~(0xff << mask);
        block[blockIndex >> 2] |= (b & 0xff) << mask;
        blockIndex++;
        if (blockIndex == 64) {
            transform();
            blockIndex = 0;
        }
    }

    /**
     * Add many bytes to the digest.
     * @param data byte data to add
     * @param offset start byte
     * @param length number of bytes to hash
     */
    @Override
    public final void update(byte[] data, int offset, int length) {
        for (int i=0; i<length; ++i)
            update(data[offset+i]);
    }

    @Override
    public final void update(byte[] data) {
        update(data, 0, data.length);
    }

    /**
     * Write completed digest into the given buffer.
     * @param reset If true, the hash function is reinitialized
     */
    @Override
    public final void digest(boolean reset, byte[] buffer, int offset) {
        finish();
        System.arraycopy(digestBits, 0, buffer, offset, digestBits.length);
        if (reset)
            init();
    }

    /**
     * Return completed digest.
     * @return the byte array result
     * @param reset If true, the hash function is reinitialized
     */
    public final byte[] digest(boolean reset) {
        byte[] out = new byte[20];
        digest(reset, out, 0);
        return out;
    }

    /**
     * Return completed digest.
     * @return the byte array result
     */
    @Override
    public final byte[] digest() {
        return digest(true);
    }

    /**
     * Complete processing on the message digest.
     */
    protected final void finish() {
        byte bits[] = new byte[8];
        int i;
        for (i = 0; i < 8; i++) {
            bits[i] = (byte)((count >>> (((7 - i) << 3))) & 0xff);
        }

        update((byte) 128);
        while (blockIndex != 56)
            update((byte) 0);
        // This should cause a transform to happen.
        for(i=0; i<8; ++i) update(bits[i]);
        for (i = 0; i < 20; i++) {
            digestBits[i] = (byte)
                ((state[i>>2] >>> ((3-(i & 3)) << 3) ) & 0xff);
        }
    }

    /**
     * Print out the digest in a form that can be easily compared
     * to the test vectors.
     */
    protected String digout() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            char c1, c2;

            c1 = (char) ((digestBits[i] >>> 4) & 0xf);
            c2 = (char) (digestBits[i] & 0xf);
            c1 = (char) ((c1 > 9) ? 'A' + (c1 - 10) : '0' + c1);
            c2 = (char) ((c2 > 9) ? 'A' + (c2 - 10) : '0' + c2);
            sb.append(c1);
            sb.append(c2);
        }
        return sb.toString();
    }


    /**
     * test driver
     */
    public static void main(String[] args) {
        SHAselfTest();
        SHAbenchTest();
    }
    
    public static void SHAbenchTest() {
	try {
	    JavaSHA1 s = new JavaSHA1();
	    genericBenchTest(s);
	    try {
		JavaSHA1 s1 = (JavaSHA1)(s.clone());
		System.err.println("Cloned successfully");
		genericBenchTest(s1);
	    } catch (CloneNotSupportedException e) {
		System.err.println("Couldn't clone");
	    }
	} catch (Exception e) {
	    System.err.println("Couldn't run benchmark for JavaSHA1");
	}
	SHA1 sha1 = new SHA1(true);
	genericBenchTest(sha1);
    }
    
    public static void genericBenchTest(Digest sha1) {
        System.out.println("\nBegin benchmark");
        long size = 4096;
        long count = 100000;
        byte data[] = createDummyData((int)size);
        long startSameUpdate = System.currentTimeMillis();
        for (int i = 0; i < count; i++) 
            sha1.update(data);
        long endSameUpdate = System.currentTimeMillis();
        long updateTime = endSameUpdate - startSameUpdate;
        long speed = 0;
        long eachTime = 0;
        if (updateTime > 0) {
            speed = size*count*1000 / updateTime;
            eachTime = count / updateTime;
        }
        System.out.println("SHA update " + count + " times for the same data block of size " + size + " bytes:");
        System.out.println(updateTime + "ms [" + speed + "bytes/second, " + eachTime + "updates/ms]\n");
        
        count = 1000;
        byte data2[][] = new byte[(int)count][];
        for (int i = 0; i < count; i++)
            data2[i] =  createDummyData((int)size);
        long differentStartTime = System.currentTimeMillis();
        for (int i = 0; i < count; i++) 
            sha1.update(data2[i]);
        long endDifferentUpdate = System.currentTimeMillis();
        updateTime = endDifferentUpdate - differentStartTime;
        speed = 0;
        eachTime = 0;
        if (updateTime > 0) {
            speed = size*count*1000 / updateTime;
            eachTime = count / updateTime;
        }
        System.out.println("SHA update " + count + " times for different data blocks of size " + size + " bytes:");
        System.out.println(updateTime + "ms [" + speed + "bytes/second, " + eachTime + "ms/update]");
        
        System.out.println("\nEnd benchmark");
    }
    
    private static byte[] createDummyData(int size) {
        Random rand = new Random();
        byte buf[] = new byte[size];
        rand.nextBytes(buf);
        return buf;
    }
    
    public static void SHAselfTest() {
	System.err.println("Testing JavaSHA1:");
	try {
	    JavaSHA1 js = new JavaSHA1();
	    SHAselfTest(js);
	} catch (Exception e) {
	    System.err.println("Caught exception "+e+
			       " trying to test JavaSHA1");
	}
	System.err.println("Testing SHA1:");
	SHA1 s = new SHA1(true);
	SHAselfTest(s);
    }
    
    /**
     * perfoms a self-test - spits out the test vector outputs on System.out
     */
    public static void SHAselfTest(Digest s) {
        int i;
        // This line may be safely deleted, its to make it easy to see
        // the output of the program.
        System.out.println("SHA-1 Test PROGRAM.");
        System.out.println("This code runs the test vectors through the code.");

/*      "abc"
        A9993E36 4706816A BA3E2571 7850C26C 9CD0D89D */

        System.out.println("First test is 'abc'");
        String z = "abc";
        //s.init();
        s.update((byte) 'a');
        s.update((byte) 'b');
        s.update((byte) 'c');
        System.out.println(HexUtil.bytesToHex(s.digest()).toUpperCase());
        System.out.println("A9993E364706816ABA3E25717850C26C9CD0D89D");

/*      "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        84983E44 1C3BD26E BAAE4AA1 F95129E5 E54670F1 */

        System.out.println("Next Test is 'abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq'");
        z = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
        //s.init(); - initted by digest above
        for (i=0; i<z.length(); ++i) {
            s.update((byte) z.charAt(i));
        }
        System.out.println(HexUtil.bytesToHex(s.digest()).toUpperCase());
        System.out.println("84983E441C3BD26EBAAE4AA1F95129E5E54670F1");
	
	/*      A million repetitions of "a"
        34AA973C D4C4DAA4 F61EEB2B DBAD2731 6534016F */
        long startTime = 0 - System.currentTimeMillis();
	
        System.out.println("Last test is 1 million 'a' characters.");
        //s.init(); - initted by digest above
        for (i = 0; i < 1000000; i++)
            s.update((byte) 'a');
        //s.finish(); - digest() finishes
        System.out.println(HexUtil.bytesToHex(s.digest()).toUpperCase());
        System.out.println("34AA973CD4C4DAA4F61EEB2BDBAD27316534016F");
        startTime += System.currentTimeMillis();
        double d = startTime/1000.0;
        System.out.println(" done, elapsed time = "+d);
    }
}


