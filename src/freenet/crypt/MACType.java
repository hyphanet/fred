/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;

/**
 * Keeps track of properties of different Message Authentication Code 
 * algorithms available to Freenet including key type, name of the 
 * algorithm, and iv length if required. 
 * @author unixninja92
 *
 */
public enum MACType {
    HMACSHA256(1, "HmacSHA256", KeyType.HMACSHA256),
    HMACSHA384(2, "HmacSHA384", KeyType.HMACSHA384),
    HMACSHA512(2, "HmacSHA512", KeyType.HMACSHA512),
    Poly1305AES(2, "POLY1305-AES", 16, KeyType.POLY1305AES);

    /** Bitmask for aggregation. */
    public final int bitmask;
    public final String mac;
    public final int ivlen;
    public final KeyType keyType;

    /**
     * Creates the HMACSHA256 enum. Sets the ivlen as -1.
     * @param bitmask
     * @param mac Name of the algorithm that java uses. 
     * @param type The type of key the alg requires
     */
    private MACType(int bitmask, String mac, KeyType type){
        this.bitmask = bitmask;
        this.mac = mac;
        ivlen = -1;
        keyType = type;
    }

    /**
     * Creates the Poly1305 enum.
     * @param bitmask
     * @param mac Name of the algorithm that java uses. 
     * @param ivlen Length of the IV
     * @param type The type of key the alg requires
     */
    private MACType(int bitmask, String mac, int ivlen, KeyType type){
        this.bitmask = bitmask;
        this.mac = mac;
        this.ivlen = ivlen;
        keyType = type;
    }

    /**
     * Gets an instance of Mac using the specified algorithm. 
     * @return Returns an instance of Mac
     */
    public final Mac get(){
        try {
            return Mac.getInstance(mac);
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e); // Definitely a bug...
        }
    }

}
