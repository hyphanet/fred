package freenet.crypt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Security;

import freenet.crypt.ECDH.Curves;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Before;
import org.junit.Test;

public class ECDHTest {
    
    ECDH.Curves curveToTest;
    ECDH alice;
    ECDH bob;
    
    @Before
    public void setUp() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        curveToTest = Curves.P256;
        alice = new ECDH(curveToTest);
        bob = new ECDH(curveToTest);
    }

    @Test
    public void testGetAgreedSecret() throws InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        byte[] aliceS = alice.getAgreedSecret(bob.getPublicKey());
        byte[] bobS = bob.getAgreedSecret(alice.getPublicKey());
        assertNotNull(aliceS);
        assertNotNull(bobS);
        assertArrayEquals(aliceS, bobS);
        assertEquals(aliceS.length, curveToTest.derivedSecretSize);
        assertEquals(bobS.length, curveToTest.derivedSecretSize);
    }

    @Test
    public void testGetPublicKey() {
        PublicKey aliceP = alice.getPublicKey();
        PublicKey bobP = bob.getPublicKey();
        
        assertNotNull(aliceP);
        assertNotSame(aliceP, bobP);
        assertEquals(aliceP.getEncoded().length, curveToTest.modulusSize);
        assertEquals(bobP.getEncoded().length, curveToTest.modulusSize);
    }
}
