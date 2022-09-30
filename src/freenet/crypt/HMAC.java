/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import freenet.support.Logger;

/**
 * Implements the HMAC Keyed Message Authentication function, as described in the draft FIPS
 * standard.
 */
public enum HMAC {
  SHA2_256("HmacSHA256", 32);

  final String algo;
  final int digestSize;

  HMAC(String name, int size) {
    this.algo = name;
    this.digestSize = size;
  }

  public static byte[] mac(HMAC hash, byte[] key, byte[] data) {
    if(key.length != hash.digestSize)
      throw new IllegalArgumentException("Wrong keysize! We're not doing key stretching "+
                                         key.length+" expected "+hash.digestSize);

    SecretKeySpec signingKey = new SecretKeySpec(key, hash.algo);
    Mac mac;
    try {
      mac = Mac.getInstance(hash.algo);
    } catch (NoSuchAlgorithmException e) {
      Logger.error(HMAC.class, "No such AlgorithmException", e);
      throw new Error(e);
    }
    try {
      mac.init(signingKey);
    } catch (InvalidKeyException e) {
      Logger.error(HMAC.class, "Impossible InvalidKeyException", e);
      throw new Error(e);
    }
    return mac.doFinal(data);
  }

  public static boolean verify(HMAC hash, byte[] key, byte[] data, byte[] mac) {
    return MessageDigest.isEqual(mac, mac(hash, key, data));
  }

  public static byte[] macWithSHA256(byte[] K, byte[] text) {
    return mac(HMAC.SHA2_256, K, text);
  }

  public static boolean verifyWithSHA256(byte[] K, byte[] text, byte[] mac) {
    return verify(HMAC.SHA2_256, K, text, mac);
  }
}	
