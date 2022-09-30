package freenet.crypt;

import junit.framework.TestCase;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;

import java.security.Security;
import java.util.Random;

import freenet.support.TestProperty;
import freenet.support.TimeUtil;

public class HMACTest extends TestCase {

  Random random;
  // RFC4868 2.7.2.1 SHA256 Authentication Test Vector
  static byte[] plaintext = "Hi There".getBytes();
  static byte[]
      knownKey =
      Hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
  static byte[]
      knownSHA256 =
      Hex.decode("198a607eb44bfbc69903a0f1cf2bbdc5ba0aa3f3d9ae3c1c7a3b1696a0b68cf7");

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
    assertEquals(Hex.toHexString(hmac), Hex.toHexString(knownSHA256));
  }

  // ant -Dtest.skip=false -Dtest.class=freenet.crypt.HMACTest -Dtest.benchmark=true unit
  public void testBenchmark() {
    if (!TestProperty.BENCHMARK) {
      return;
    }

    int count = 0;
    System.out.println("We're getting ready to benchmark HMACs");
    Random r = new Random(0xBBBBBBBB);
    for (int len = 8; len <= 32768; len *= 4) {
      byte [] plaintext = new byte[len];
      r.nextBytes(plaintext);
      System.out.println("plaintext len "+len);
      int ITERATIONS = 10000000/len;
      long t1 = System.currentTimeMillis();
      for (int i = 0; i < ITERATIONS; i++) {
        byte[] r1 = HMAC_legacy.macWithSHA256(knownKey, plaintext, 32);
        for (int j = 0; j < r1.length; j++) {
          count += r1[j];
        }
      }
      long legacyLength = System.currentTimeMillis() - t1;

      t1 = System.currentTimeMillis();
      for (int i = 0; i < ITERATIONS; i++) {
        byte[] r1 = HMAC.macWithSHA256(knownKey, plaintext);
        for (int j = 0; j < r1.length; j++) {
          count += r1[j];
        }
      }
      long currentLength = System.currentTimeMillis() - t1;

      t1 = System.currentTimeMillis();
      for (int i = 0; i < ITERATIONS; i++) {
        byte[] r1 = new byte[32];
        HMac hmac = new HMac(new SHA256Digest());
        KeyParameter kp = new KeyParameter(knownKey);
        hmac.init(kp);
        hmac.update(plaintext, 0, plaintext.length);
        hmac.doFinal(r1, 0);
        for (int j = 0; j < r1.length; j++) {
          count += r1[j];
        }
      }
      long BCLength = System.currentTimeMillis() - t1;
      System.out.println("Legacy HMAC took " + TimeUtil.formatTime(legacyLength, 6, true));
      System.out.println("Current HMAC took " + TimeUtil.formatTime(currentLength, 6, true));
      System.out.println("BC HMAC took " + TimeUtil.formatTime(BCLength, 6, true));
    }
  }
}
