package freenet.crypt;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import freenet.crypt.ECDH.Curves;
import junit.framework.TestCase;

public class ECDHTest extends TestCase {
    
    ECDH.Curves curveToTest;
    ECDH alice;
    ECDH bob;
    
    protected void setUp() throws Exception {
        super.setUp();
        Security.addProvider(new BouncyCastleProvider());
        curveToTest = Curves.P256;
        alice = new ECDH(curveToTest);
        bob = new ECDH(curveToTest);
    }

    public void testGetAgreedSecret() throws InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        byte[] aliceS = alice.getAgreedSecret(bob.getPublicKey());
        byte[] bobS = bob.getAgreedSecret(alice.getPublicKey());
        assertNotNull(aliceS);
        assertNotNull(bobS);
        assertEquals(toHex(aliceS), toHex(bobS));
        assertEquals(aliceS.length, curveToTest.derivedSecretSize);
        assertEquals(bobS.length, curveToTest.derivedSecretSize);
    }

    public void testGetPublicKey() {
        PublicKey aliceP = alice.getPublicKey();
        PublicKey bobP = bob.getPublicKey();
        
        assertNotNull(aliceP);
        assertNotSame(aliceP, bobP);
        assertEquals(aliceP.getEncoded().length, curveToTest.modulusSize);
        assertEquals(bobP.getEncoded().length, curveToTest.modulusSize);
    }


    public static void main(String[] args) throws InvalidKeyException, IllegalStateException, NoSuchAlgorithmException {
        Security.addProvider(new BouncyCastleProvider());
        
        ECDH alice = new ECDH(Curves.P256);
        ECDH bob = new ECDH(Curves.P256);
        PublicKey bobP = bob.getPublicKey();
        PublicKey aliceP = alice.getPublicKey();
        
        System.out.println("Alice C: "+alice.curve);
        System.out.println("Bob   C: "+bob.curve);
        System.out.println("Alice P: "+toHex(aliceP.getEncoded()));
        System.out.println("Bob   P: "+toHex(bobP.getEncoded()));
        
        System.out.println("Alice S: "+toHex(alice.getAgreedSecret(bob.getPublicKey())));
        System.out.println("Bob   S: "+toHex(bob.getAgreedSecret(alice.getPublicKey())));
    }
    
    public static String toHex(byte[] arg) {
        return String.format("%040x", new BigInteger(1,arg));
    }
    
    public static String toHex(String arg) throws UnsupportedEncodingException {
        return toHex(arg.getBytes("utf-8"));
    }
}
