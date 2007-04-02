/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 *
 * DSATest.java
 * JUnit based test
 *
 * @author sback
 */

package freenet.crypt;


import java.math.BigInteger;
import java.util.Random;
import junit.framework.Test;
import junit.framework.TestCase;
import net.i2p.util.NativeBigInteger;


public class DSATest extends TestCase{
    
    /*-------------FIPS-EXAMPLE-CONSTANTS---------------------------------------
     * These are the values as they appear in the Appendix 5
     * "Example of the DSA" of FIPS PUB 186-2.
     * We can consider them sure examples */
    private static final BigInteger FIPS_P = new NativeBigInteger(
                                "8df2a494492276aa3d25759bb06869cbeac0d83afb8d0cf7"+
                                "cbb8324f0d7882e5d0762fc5b7210eafc2e9adac32ab7aac"+
                                "49693dfbf83724c2ec0736ee31c80291",16);
    private static final BigInteger FIPS_Q = new NativeBigInteger(
                                "c773218c737ec8ee993b4f2ded30f48edace915f",16);
    private static final BigInteger FIPS_G = new NativeBigInteger(
                                "626d027839ea0a13413163a55b4cb500299d5522956cefcb"+
                                "3bff10f399ce2c2e71cb9de5fa24babf58e5b79521925c9c"+
                                "c42e9f6f464b088cc572af53e6d78802",16);
    private static final BigInteger FIPS_X = new NativeBigInteger(
                                "2070b3223dba372fde1c0ffc7b2e3b498b260614",16);
    private static final BigInteger FIPS_Y = new NativeBigInteger(
                                "19131871d75b1612a819f29d78d1b0d7346f7aa77bb62a85"+
                                "9bfd6c5675da9d212d3a36ef1672ef660b8c7c255cc0ec74"+
                                "858fba33f44c06699630a76b030ee333",16);
    private static final BigInteger FIPS_K = new NativeBigInteger(
                                "358dad571462710f50e254cf1a376b2bdeaadfbf",16);
    private static final BigInteger FIPS_K_INV = new NativeBigInteger(
                                "0d5167298202e49b4116ac104fc3f415ae52f917",16);
    private static final BigInteger FIPS_SHA1_M = new NativeBigInteger(
                                "a9993e364706816aba3e25717850c26c9cd0d89d",16);
    private static final BigInteger FIPS_R = new NativeBigInteger(
                                "8bac1ab66410435cb7181f95b16ab97c92b341c0",16);
    private static final BigInteger FIPS_S = new NativeBigInteger(
                                "41e2345f1f56df2458f426d155b4ba2db6dcd8c8",16);
    private static final DSAGroup FIPS_DSA_GROUP = 
                    new DSAGroup(FIPS_P,FIPS_Q,FIPS_G);
    private static final DSAPrivateKey FIPS_DSA_PRIVATE_KEY = 
                    new DSAPrivateKey(FIPS_X);
    private static final DSAPublicKey FIPS_DSA_PUBLIC_KEY =
                    new DSAPublicKey(FIPS_DSA_GROUP,FIPS_Y);
    private static final DSASignature FIPS_DSA_SIGNATURE = 
                    new DSASignature(FIPS_R,FIPS_S);
    /*------------------------------------------------------------------------*/
    
    private RandomSource randomSource;
    
    public DSATest(String testName) { super(testName); }

    protected void setUp() throws Exception {
        randomSource = new DummyRandomSource();
    }

    protected void tearDown() throws Exception {}

    /**
     * Test of verify and sign method consistency using FIPS examples.*/
    public void testSignAndVerify() {
        System.out.println("signAndVerify");
        
        DSASignature aSignature = 
                DSA.sign(FIPS_DSA_GROUP, FIPS_DSA_PRIVATE_KEY, FIPS_K, FIPS_SHA1_M, randomSource);
        
        assertTrue(DSA.verify(FIPS_DSA_PUBLIC_KEY,aSignature,FIPS_SHA1_M,false));
    }
    
    /** Test of verify(DSAPublicKey kp,
			DSASignature sig,
			BigInteger m, boolean forceMod)
     * method comparing it with the DSA values
     * based on FIPS examples */
    public void testVerify() {
        System.out.println("testVerify");
        
        assertTrue(DSA.verify(FIPS_DSA_PUBLIC_KEY,FIPS_DSA_SIGNATURE,FIPS_SHA1_M,false));
    }
    
    /**
     * Test sign method consistency
     * It performs two signature of the same message
     * and verifies if they are identical */
    public void testSameSignConsistency() {
        System.out.println("sameSignConsistency");
        
        DSASignature firstSignature = 
                DSA.sign(FIPS_DSA_GROUP, FIPS_DSA_PRIVATE_KEY, FIPS_K, FIPS_SHA1_M, randomSource);
        
        DSASignature secondSignature = 
                DSA.sign(FIPS_DSA_GROUP, FIPS_DSA_PRIVATE_KEY, FIPS_K, FIPS_SHA1_M, randomSource);
        
        assertEquals(firstSignature.getR(),secondSignature.getR());
        assertEquals(firstSignature.getS(),secondSignature.getS());
    }

    /**
     * Test sign(DSAGroup g, DSAPrivateKey x, BigInteger m,RandomSource r)
     * method, using a q value that is too small [shorter than DSAGroup.Q_BIT_LENGTH]
     * to generate a correct k value */
    public void testSignSmallQValue(){
        System.out.println("signWithSmallQValue");
        try {
            DSA.sign(FIPS_DSA_GROUP,FIPS_DSA_PRIVATE_KEY,FIPS_SHA1_M,randomSource);
        } catch (AssertionError anAssertionError) {
            assertNotNull(anAssertionError);
        }
    }
    
    /**
     * Test sign(DSAGroup g, DSAPrivateKey x,
			BigInteger r, BigInteger kInv, 
			BigInteger m, RandomSource random)
     * method comparing it with the DSASignature
     * based on FIPS examples */
    public void testSign_grp_pvtKey_r_kInv_m_rand() {
        System.out.println("sign(grp,pvtKey,r,kInv,m,rand)");
        
        DSASignature aSignature =
                DSA.sign(FIPS_DSA_GROUP,FIPS_DSA_PRIVATE_KEY,FIPS_R,FIPS_K_INV,FIPS_SHA1_M,randomSource);
        
        assertEquals(aSignature.getR(),FIPS_R);
        assertEquals(aSignature.getS(),FIPS_S);
    }
    
    /**
     * Test sign(DSAGroup g,
			DSAPrivateKey x,
			BigInteger k, 
			BigInteger m,
			RandomSource random)
     * method comparing it with the DSASignature
     * based on FIPS examples */
    public void testSign_grp_pvtKey_k_m_rand() {
        System.out.println("sign(grp,pvtKey,k,m,rand)");
        
        DSASignature aSignature =
                DSA.sign(FIPS_DSA_GROUP,FIPS_DSA_PRIVATE_KEY,FIPS_K,FIPS_SHA1_M,randomSource);
        
        assertEquals(aSignature.getR(),FIPS_R);
        assertEquals(aSignature.getS(),FIPS_S);
    }
    
    /**
     * Test sign(DSAGroup g, DSAPrivateKey x, BigInteger m,
			RandomSource r)
     * method using verify method.
     * As the verify test passed, then we can consider it 
     * a tested way to verify this sign method accuracy, 
     * since it is impossible to test it using
     * FIPS Examples [as they are based on SHA1 and thus they use
     * q values that are shorter than current DSAGroup.Q_BIT_LENGTH].*/
    public void testSign_grp_pvtKey_m_rand() {
        System.out.println("sign(grp,pvtKey,m,rand)");
        
        DSAGroup aDSAgroup = Global.DSAgroupBigA;
        
        System.out.println(aDSAgroup.getQ().bitCount());
        
        DSAPrivateKey aDSAPrivKey=new DSAPrivateKey(aDSAgroup,randomSource);
        DSAPublicKey aDSAPubKey=new DSAPublicKey(aDSAgroup,aDSAPrivKey);
	DSASignature aSignature=
                DSA.sign(aDSAgroup,aDSAPrivKey,BigInteger.ZERO,randomSource);
        
        assertTrue(DSA.verify(aDSAPubKey,aSignature,BigInteger.ZERO,false));
    }
    

}
  
    
        
