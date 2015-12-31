package freenet.crypt;

import junit.framework.TestCase;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;

import java.security.Security;
import java.util.Random;

public class HMACTest extends TestCase {

  Random random;
  // RFC4868 2.7.2.1 SHA256 Authentication Test Vector
  static byte[]   plaintext = "Hi There".getBytes();
  static byte[] knownKey = Hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
  static byte[] knownSHA256 = Hex.decode("198a607eb44bfbc69903a0f1cf2bbdc5ba0aa3f3d9ae3c1c7a3b1696a0b68cf7");

  protected void setUp() throws Exception {
    super.setUp();
    Security.addProvider(new BouncyCastleProvider());
    random = new Random(0xAAAAAAAA);
  }

  public void testAllCipherNames() {
    for (HMAC hmac : HMAC.values()) {
      HMAC.mac(hmac, new byte[hmac.digestSize], plaintext);
    }
  }

  public void testSHA256SignVerify() {
    byte[] key = new byte[32];
    random.nextBytes(key);

    byte[] hmac = HMAC.macWithSHA256(key, plaintext);
    assertNotNull(hmac);
    assertTrue(HMAC.verifyWithSHA256(key, plaintext, hmac));
  }

  public void testWrongKeySize() {
    byte[] keyTooLong = new byte[31];
    byte[] keyTooShort = new byte[29];
    random.nextBytes(keyTooLong);
    random.nextBytes(keyTooShort);
    try {
      HMAC.macWithSHA256(keyTooLong, plaintext);
      fail();
    } catch (IllegalArgumentException e) {
      // This is expected
    }
    try {
      HMAC.macWithSHA256(keyTooShort, plaintext);
      fail();
    } catch (IllegalArgumentException e) {
      // This is expected
    }
  }

  public void testKnownVectors() {
      byte[] hmac = HMAC.macWithSHA256(knownKey, plaintext);
      assertEquals(Hex.toHexString(hmac),Hex.toHexString(knownSHA256));
  }
}
