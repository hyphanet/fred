/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import freenet.support.Logger;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * Implements the HMAC Keyed Message Authentication function, as described
 * in the draft FIPS standard.
 * 
 * Use a KeyGenerator to generate a key!
 */
public class HMAC {

    public enum ALGORITHM {

        HmacSHA256, Hmac1, HmacMD5;
    }

    public static byte[] mac(ALGORITHM algo, SecretKey key, byte[] input) throws InvalidKeyException {
        try {
            Mac mac = Mac.getInstance(algo.name());
            mac.init(key);

            return mac.doFinal();
        } catch (NoSuchAlgorithmException ex) {
            Logger.error(HMAC.class, "Check your JVM settings especially the JCE!" + ex.getMessage(), ex);
            System.err.println("Check your JVM settings especially the JCE!" + ex);
            ex.printStackTrace();
            throw new Error("Check your JVM settings especially the JCE!", ex);
        }
    }

    public static boolean verify(ALGORITHM algo, SecretKey key, byte[] input, byte[] mac) throws InvalidKeyException {
        return Arrays.equals(mac, mac(algo, key, input));
    }
}