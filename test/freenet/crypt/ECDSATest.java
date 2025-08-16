package freenet.crypt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.PublicKey;

import freenet.crypt.ECDSA.Curves;
import freenet.node.FSParseException;
import freenet.support.SimpleFieldSet;
import org.junit.Before;
import org.junit.Test;


public class ECDSATest {
    
    ECDSA.Curves curveToTest;
    ECDSA ecdsa;

    @Before
    public void setUp() throws Exception {
        curveToTest = Curves.P256;
        ecdsa = new ECDSA(curveToTest);
    }

    @Test
    public void testGetPublicKey() {
        PublicKey pub = ecdsa.getPublicKey();
        assertNotNull(pub);
        assertTrue(pub.getEncoded().length <= curveToTest.modulusSize);
    }
    
    @Test
    public void testSign() {
        byte[] sig= ecdsa.sign("test".getBytes());
        assertNotNull(sig);
        assertTrue(sig.length > 0);
    }
    
    @Test
    public void testSignToNetworkFormat() {
        byte[] toSign = "test".getBytes();
        byte[] sig= ecdsa.signToNetworkFormat(toSign);
        assertNotNull(sig);
        assertEquals(sig.length, curveToTest.maxSigSize);
    }
    
    @Test
    public void testVerify() {
        String toSign = "test";
        byte[] sig= ecdsa.sign(toSign.getBytes());
        assertTrue(ecdsa.verify(sig, toSign.getBytes()));
        assertFalse(ecdsa.verify(sig, "".getBytes()));
    }
    
    @Test
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
    
    @Test
    public void testSerializeUnserialize() throws FSParseException {
        SimpleFieldSet sfs = ecdsa.asFieldSet(true);
        ECDSA ecdsa2 = new ECDSA(sfs.getSubset(curveToTest.name()), curveToTest);
        assertEquals(ecdsa.getPublicKey(), ecdsa2.getPublicKey());
    }
}
