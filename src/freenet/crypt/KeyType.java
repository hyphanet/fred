/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

/**
 * Keeps track of keysizes, ivsizes, and names of keygen algorithm names for all of the one-way and 
 * symmetric encryption schemes available to Freenet. 
 * @author unixninja92
 *
 */
public enum KeyType {
    Rijndael128("RIJNDAEL", 128),
    Rijndael256("RIJNDAEL", 256),
    AES128("AES", 128),
    AES256("AES", 256),
    HMACSHA256("HMACSHA256", 256),
    HMACSHA384("HMACSHA384", 384),
    HMACSHA512("HMACSHA512", 512),
    POLY1305AES("POLY1305-AES", 256, 128),
    ChaCha128("CHACHA", 128, 64),
    ChaCha256("CHACHA", 256, 64);

    public final String alg;
    public final int keySize;//bits
    public final int ivSize;//bits

    /**
     * Creates an enum value for the specified algorithm and keysize. ivSize is set to keySize
     * @param alg The name of the algorithm KeyGenerator should use to create a key
     * @param keySize The size of the key that KeyGenerator should generate
     */
    private KeyType(String alg, int keySize){
        this.alg = alg;
        this.keySize = keySize;
        this.ivSize = keySize;
    }

    /**
     * Creates an enum value for the specified algorithm, keySize, and ivSize
     * @param alg The name of the algorithm KeyGenerator should use to create a key
     * @param keySize The size of the key that KeyGenerator should generate
     * @param ivSize The size of the iv that should be generated
     */
    private KeyType(String alg, int keySize, int ivSize){
        this.alg = alg;
        this.keySize = keySize;
        this.ivSize = ivSize;
    }
}
