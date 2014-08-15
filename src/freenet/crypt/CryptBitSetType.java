/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.Serializable;

/**
 * Keeps track of properties of different symmetric cipher algorithms
 * available to Freenet including key type, name of the algorithm, 
 * block size used, and iv length if required. 
 * @author unixninja92
 *
 */
public enum CryptBitSetType implements Serializable{
    @Deprecated
    RijndaelECB(1, KeyType.Rijndael256),
    @Deprecated
    RijndaelECB128(2, KeyType.Rijndael256, 128),
    @Deprecated
    RijndaelPCFB(8, 32, KeyType.Rijndael256),
    AESCTR(16, 16, "AES/CTR/NOPADDING", KeyType.AES256),
    ChaCha128(32, 8, "CHACHA", KeyType.ChaCha128),
    ChaCha256(64, 8, "CHACHA", KeyType.ChaCha256);

    /** Bitmask for aggregation. */
    public final int bitmask;
    public final int blockSize;
    public final int ivSize; // in bytes
    public final String algName;
    public final String cipherName;
    public final KeyType keyType;

    /**
     * Creates the RijndaelECB enum value. iv is -1. 
     * @param bitmask
     * @param keyType The type of key the alg requires
     */
    private CryptBitSetType(int bitmask, KeyType keyType){
        this.bitmask = bitmask;
        this.keyType = keyType;
        this.cipherName = keyType.alg;
        this.blockSize = keyType.keySize;
        this.ivSize = -1;
        algName = name();
    }

    /**
     * Creates the RijndaelECB128 enum value. iv is -1. 
     * and sets the non-standard blocksize. 
     * @param bitmask
     * @param keyType The type of key the alg requires
     * @param blockSize The blocksize the alg uses
     */
    private CryptBitSetType(int bitmask, KeyType keyType, int blockSize){
        this.bitmask = bitmask;
        this.ivSize = -1;
        this.keyType = keyType;
        this.cipherName = keyType.alg;
        this.blockSize = blockSize;
        algName = name();
    }

    /**
     * Creates the RijndaelPCFB enum value.
     * @param bitmask
     * @param ivSize Size of the iv
     * @param keyType The type of key the alg requires
     */
    private CryptBitSetType(int bitmask, int ivSize, KeyType keyType){
        this.bitmask = bitmask;
        this.keyType = keyType;
        this.cipherName = keyType.alg;
        this.blockSize = keyType.keySize;
        this.ivSize = ivSize;
        algName = name();
    }

    /**
     * Creates an enum value for the specified algorithm, keytype, and iv size. 
     * Also stores the name of the alg that java recognizes 
     * @param bitmask
     * @param ivSize Size of the iv
     * @param algName The name the java provider uses for the alg
     * @param keyType The type of key the alg requires
     */
    private CryptBitSetType(int bitmask, int ivSize, String algName, KeyType keyType){
        this.bitmask = bitmask;
        this.ivSize = ivSize;
        this.cipherName = keyType.alg;
        this.blockSize = keyType.keySize;
        this.algName = algName;
        this.keyType = keyType;
    }
}
