package freenet.crypt;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.PublicKey;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import freenet.crypt.ECDSA.Curves;
import junit.framework.TestCase;

public class ECDSATest extends TestCase {
    
    ECDSA.Curves curveToTest;
    ECDSA ecdsa;

    protected void setUp() throws Exception {
        super.setUp();
        Security.addProvider(new BouncyCastleProvider());
        curveToTest = Curves.P256;
        ecdsa = new ECDSA(curveToTest);
    }

    public void testGetPublicKey() {
        PublicKey pub = ecdsa.getPublicKey();
        assertNotNull(pub);
        assertEquals(pub.getEncoded().length, curveToTest.modulusSize);
    }
    
    public void testSign() {
        byte[] sig= ecdsa.sign("test".getBytes());
        assertNotNull(sig);
        assertTrue(sig.length > 0);
    }
    
    public void testVerify() {
        String toSign = "test";
        byte[] sig= ecdsa.sign(toSign.getBytes());
        assertTrue(ecdsa.verify(sig, toSign.getBytes()));
        assertFalse(ecdsa.verify(sig, "".getBytes()));
    }
    
    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        Curves curve = ECDSA.Curves.P256;
        ECDSA ecdsa = new ECDSA(curve);
        String toSign = "test";
        byte[] signedBytes = toSign.getBytes("utf-8");
        byte[] sig = ecdsa.sign(signedBytes);
        System.out.println("Curve in use : " + curve.toString());
        System.out.println(ecdsa.getPublicKey().toString());
        System.out.println("ToSign   : "+toSign + " ("+toHex(signedBytes)+")");
        System.out.println("Signature: "+ toHex(sig));
        System.out.println("Verify?  : "+ecdsa.verify(sig, signedBytes));
    }
    
    public static String toHex(byte[] arg) {
        return String.format("%040x", new BigInteger(1,arg));
    }
    
    public static String toHex(String arg) throws UnsupportedEncodingException {
        return toHex(arg.getBytes("utf-8"));
    }

}
