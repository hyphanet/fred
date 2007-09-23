/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.crypt;

import java.math.BigInteger;
import junit.framework.TestCase;
import net.i2p.util.NativeBigInteger;

/**
 * Test case for {@link freenet.crypt.DSA} class.
 *
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
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
    
    public DSATest(String testName) { 
        super(testName); 
    }

    protected void setUp() throws Exception {
        randomSource = new DummyRandomSource();
    }

    protected void tearDown() throws Exception {}
    
    /**
     * Test of verify and sign method consistency using FIPS examples.*/
    public void testSignAndVerify() {
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
        assertTrue(DSA.verify(FIPS_DSA_PUBLIC_KEY,FIPS_DSA_SIGNATURE,FIPS_SHA1_M,false));
    }
    
    /**
     * Test sign method consistency
     * It performs two signature of the same message
     * and verifies if they are identical */
    public void testSameSignConsistency() {
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
        try {
            DSA.sign(FIPS_DSA_GROUP,FIPS_DSA_PRIVATE_KEY,FIPS_SHA1_M,randomSource);
            fail("Exception Error Not Thrown!");
        } catch (IllegalArgumentException anException) {
            assertNotNull(anException);
        }
    }
    
    /**
     * Test sign(DSAGroup g, DSAPrivateKey x,
			BigInteger r, BigInteger kInv, 
			BigInteger m, RandomSource random)
     * method comparing it with the DSASignature
     * based on FIPS examples */
    public void testSign_grp_pvtKey_r_kInv_m_rand() {
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
        BigInteger x = FIPS_X;
        DSAPrivateKey aTestPrivateKey;
        DSASignature anotherSignature;
        
        DSASignature aSignature =
                DSA.sign(FIPS_DSA_GROUP,FIPS_DSA_PRIVATE_KEY,FIPS_K,FIPS_SHA1_M,randomSource);
        
        BigInteger generatedR = aSignature.getR();
        BigInteger generatedS = aSignature.getS();
        
        assertEquals(generatedR,FIPS_R);
        assertEquals(generatedS,FIPS_S);
        
        
        for(int i = 0; i <= FIPS_X.bitCount(); i++) {
            x = x.flipBit(i);
            if (x.compareTo(FIPS_X) != 0) {
                aTestPrivateKey = new DSAPrivateKey(x);
                anotherSignature = DSA.sign(FIPS_DSA_GROUP,aTestPrivateKey,FIPS_K,FIPS_SHA1_M,randomSource);
                assertFalse(generatedR.compareTo(anotherSignature.getR()) == 0 && 
                            generatedS.compareTo(anotherSignature.getS()) == 0);
            }
        }
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
        DSAGroup aDSAgroup = Global.DSAgroupBigA;
        
        DSAPrivateKey aDSAPrivKey=new DSAPrivateKey(aDSAgroup,randomSource);
        DSAPublicKey aDSAPubKey=new DSAPublicKey(aDSAgroup,aDSAPrivKey);
        DSASignature aSignature=
                DSA.sign(aDSAgroup,aDSAPrivKey,aDSAPrivKey.getX(),randomSource);
        
        assertTrue(DSA.verify(aDSAPubKey,aSignature,aDSAPrivKey.getX(),false));
    }
    
    /* The following tests still generates problem,
     * they are commented so they could be useful to
     * check for bugs
     */
    
    /*
    public void testSign_border() {
        BigInteger k = BigInteger.ONE;
        BigInteger q = Global.DSAgroupBigA.getQ().add(BigInteger.ONE);
        BigInteger p = q;
        BigInteger g = p.add(BigInteger.ONE);
        
        DSAGroup aDSAgroup = new DSAGroup(p,q,g);
        
        DSAPrivateKey aDSAPrivKey=new DSAPrivateKey(aDSAgroup,randomSource);
        DSAPublicKey aDSAPubKey=new DSAPublicKey(aDSAgroup,aDSAPrivKey);
		DSASignature aSignature=
                DSA.sign(aDSAgroup,aDSAPrivKey,k,BigInteger.TEN,randomSource);
        
        System.out.println(aSignature.toLongString());
        System.out.println(aDSAPrivKey.toLongString());
        System.out.println(aDSAPubKey.toLongString());
        
        assertTrue(DSA.verify(aDSAPubKey,aSignature,BigInteger.TEN,false));
    }
    
    /**
     * Test sign(DSAGroup g, DSAPrivateKey x, BigInteger m,
			RandomSource r)
     * method in a not valid case (i.e. *
    public void testSign_border2() {
        System.out.println("sign(grp,pvtKey,m,rand)");
        
        BigInteger q = BigInteger.ONE;
        //BigInteger p = q;
        //BigInteger g = (p.multiply(BigInteger.ONE)).add(BigInteger.ONE);
        
        DSAGroup aDSAgroup = new DSAGroup(Global.DSAgroupBigA.getP(),
                                          q,Global.DSAgroupBigA.getG());
        
        DSAPrivateKey aDSAPrivKey=new DSAPrivateKey(aDSAgroup,randomSource);
        DSAPublicKey aDSAPubKey=new DSAPublicKey(aDSAgroup,aDSAPrivKey);
		DSASignature aSignature=
                DSA.sign(aDSAgroup,aDSAPrivKey,aDSAPrivKey.getX(),randomSource);
        
        assertTrue(DSA.verify(aDSAPubKey,aSignature,aDSAPrivKey.getX(),false));
    } */
}