package freenet.crypt;

import java.math.BigInteger;

import net.i2p.util.NativeBigInteger;

import freenet.support.HexUtil;

import junit.framework.TestCase;

/**
 * Contains Unit testing methods for the crypt functions.
 */
public class CryptTest extends TestCase {

    public static final void main(String[] args) {
        freenet.support.test.SimpleTestRunner.main(
            new String[] { CryptTest.class.getName() }
        );
    }

    public CryptTest(String name) {
        super(name);
    }

    private static byte[][] dsaKeys = {
    	HexUtil.hexToBytes("5e2b3cb3e6d6b378351eaa90853c01300db462dd"),
		HexUtil.hexToBytes("8564694b62daafc23133f90ed7837a51902c3163"),
		HexUtil.hexToBytes("b710dbf353e046985124ff55536db97b0c6b927e"),
		HexUtil.hexToBytes("d977a0569342765d6436d69179dbd5be39aa0565") 
    };
    
    private static Yarrow y = new Yarrow();

    /**
     * Tests DSA signatures.
     */
    public void testDSA() {
        DSAGroup g=Global.DSAgroupC;
        for (int i = 0 ; i < dsaKeys.length ; i++) {
            DSAPrivateKey pk=new DSAPrivateKey(new NativeBigInteger(1, dsaKeys[i]));
            DSAPublicKey pub=new DSAPublicKey(g, pk);
            DSASignature sig=DSA.sign(g, pk, BigInteger.ZERO, y);
            assertTrue("Testing that signature verifies",
                       DSA.verify(pub, sig, BigInteger.ZERO));
        }
    }

    /**
     * Tests ElGamal encryption.
     */
    //public void testElgamal() {
    //    BigInteger M=new BigInteger("1234567891011121314151617181920");
    //    for (int i = 0; i < dsaKeys.length ; i++) {
    //        DSAPrivateKey priv = new DSAPrivateKey(new BigInteger(1, dsaKeys[i]));
    //        DSAPublicKey kp=
    //            new DSAPublicKey(Global.DSAgroupA,
    //                             priv);
    //        BigInteger[] C=ElGamal.encrypt(kp, M, y);
    //
    //        BigInteger R=ElGamal.decrypt(Global.DSAgroupA, priv, C);
    //
    //        assertEquals("Test encrypt*decrypt=I",M,R);
    //    }
    //}

    /**
     * Tests SHA1 using the standard test vectors.
     */
    public void testSHA1() {
        int i, j;
        SHA1 s = (SHA1)SHA1.getInstance();

/*      "abc"
        A9993E36 4706816A BA3E2571 7850C26C 9CD0D89D */

        String z = "abc";
        s.init();
        s.update((byte) 'a');
        s.update((byte) 'b');
        s.update((byte) 'c');
        s.finish();
        assertEquals("Testing hash of 'abc'", 
                     "A9993E364706816ABA3E25717850C26C9CD0D89D",
                     s.digout());
        
/*      "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        84983E44 1C3BD26E BAAE4AA1 F95129E5 E54670F1 */

        z = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
        s.init();
        for( i=0; i<z.length(); ++i)
        {
        	s.update((byte) z.charAt(i));
        }
        s.finish();
        assertEquals("Testing hash of 'abcdbcdecdefdefgefghfghighijhi" +
                     "jkijkljklmklmnlmnomnopnopq'",
                     "84983E441C3BD26EBAAE4AA1F95129E5E54670F1",
                     s.digout());

/*      A million repetitions of "a"
        34AA973C D4C4DAA4 F61EEB2B DBAD2731 6534016F */

        s.init();
        for (i = 0; i < 1000000; i++)
            s.update((byte) 'a');
        s.finish();
        assertEquals("Testing hash of a millis 'a' chars",
                     "34AA973CD4C4DAA4F61EEB2BDBAD27316534016F",
                     s.digout());
    }

}
