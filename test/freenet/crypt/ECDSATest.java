package freenet.crypt;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.PublicKey;

import freenet.crypt.ECDSA.Curves;
import freenet.node.FSParseException;
import freenet.support.SimpleFieldSet;
import junit.framework.TestCase;


public class ECDSATest extends TestCase {
    
    ECDSA.Curves curveToTest;
    ECDSA ecdsa;

    protected void setUp() throws Exception {
        super.setUp();
        curveToTest = Curves.P256;
        ecdsa = new ECDSA(curveToTest);
    }

    public void testGetPublicKey() {
        PublicKey pub = ecdsa.getPublicKey();
        assertNotNull(pub);
        assertTrue(pub.getEncoded().length <= curveToTest.modulusSize);
    }
    
    public void testSign() {
        byte[] sig= ecdsa.sign("test".getBytes());
        assertNotNull(sig);
        assertTrue(sig.length > 0);
    }
    
    public void testSignToNetworkFormat() {
        byte[] toSign = "test".getBytes();
        byte[] sig= ecdsa.signToNetworkFormat(toSign);
        assertNotNull(sig);
        assertEquals(sig.length, curveToTest.maxSigSize);
    }
    
    public void testVerify() {
        String toSign = "test";
        byte[] sig= ecdsa.sign(toSign.getBytes());
        assertTrue(ecdsa.verify(sig, toSign.getBytes()));
        assertFalse(ecdsa.verify(sig, "".getBytes()));
    }
    
    public void testAsFieldSet() throws FSParseException {
        SimpleFieldSet privSFS = ecdsa.asFieldSet(true);
        assertNotNull(privSFS.getSubset(curveToTest.name()));
        assertNotNull(privSFS.get(curveToTest.name()+".pub"));
        assertNotNull(privSFS.get(curveToTest.name()+".pri"));

        // Ensure we don't leak the privkey when we don't intend to
        SimpleFieldSet pubSFS = ecdsa.asFieldSet(false);
        assertNotNull(pubSFS.getSubset(curveToTest.name()));
        assertNotNull(pubSFS.get(curveToTest.name()+".pub"));
        assertNull(pubSFS.get(curveToTest.name()+".pri"));
    }
    
    public void testSerializeUnserialize() throws FSParseException {
        SimpleFieldSet sfs = ecdsa.asFieldSet(true);
        ECDSA ecdsa2 = new ECDSA(sfs.getSubset(curveToTest.name()), curveToTest);
        assertEquals(ecdsa.getPublicKey(), ecdsa2.getPublicKey());
    }
    
    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        Curves curve = ECDSA.Curves.P256;
        ECDSA ecdsa = new ECDSA(curve);
        String toSign = "test";
        byte[] signedBytes = toSign.getBytes("utf-8");
        //byte[] sig = ecdsa.sign(signedBytes);
        byte[] sig = ecdsa.signToNetworkFormat(signedBytes);
        System.out.println("Curve in use : " + curve.toString());
        System.out.println(ecdsa.getPublicKey().toString());
        System.out.println("ToSign   : "+toSign + " ("+toHex(signedBytes)+")");
        System.out.println("Signature: "+ toHex(sig));
        System.out.println("Verify?  : "+ecdsa.verify(sig, signedBytes));
        
        SimpleFieldSet sfs = ecdsa.asFieldSet(true);
        System.out.println("\nSerialized to: ");
        System.out.println(sfs.toString());
        System.out.println("Restored to: ");
        ECDSA ecdsa2 = new ECDSA(sfs.getSubset(curve.name()), curve);
        System.out.println(ecdsa2.getPublicKey());
        System.out.println("Verify?  : "+ecdsa2.verify(sig, signedBytes));
        
        System.out.println("Let's ensure that the signature always fits into "+ecdsa.curve.maxSigSize+" bytes.");
        int max = 0;
        for(int i=0; i<10000; i++) {
            max = Math.max(max, ecdsa.sign(signedBytes).length);
        }
        System.out.println(max);
    }
    
    public static String toHex(byte[] arg) {
        return String.format("%040x", new BigInteger(1,arg));
    }
    
    public static String toHex(String arg) throws UnsupportedEncodingException {
        return toHex(arg.getBytes("utf-8"));
    }

}
