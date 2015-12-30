/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.util.HashMap;
import java.util.Map;

import org.bouncycastle.crypto.SkippingStreamCipher;
import org.bouncycastle.crypto.engines.ChaChaEngine;

/**
 * Stores information about the algorithms used, the version number, and the footer length for a
 * EncryptedRandomAccessBuffer
 * @author unixninja92
 *
 */
public enum EncryptedRandomAccessBufferType {
    ChaCha128(1, 12, CryptByteBufferType.ChaCha128, MACType.HMACSHA256, 32),
    ChaCha256(2, 12, CryptByteBufferType.ChaCha256, MACType.HMACSHA256, 32);

    public final int bitmask;
    public final int headerLen;//bytes
    public final CryptByteBufferType encryptType;
    public final KeyType encryptKey;
    public final MACType macType;
    public final KeyType macKey;
    public final int macLen;//bytes
    
    /**
     * Creates the ChaCha enum values. 
     * @param bitmask The version number
     * @param magAndVerLen Length of magic value and version
     * @param type Alg to use for encrypting the data
     * @param macType Alg to use for MAC generation
     * @param macLen The length of the MAC output in bytes
     */
    private EncryptedRandomAccessBufferType(int bitmask, int magAndVerLen, CryptByteBufferType type, 
            MACType macType, int macLen){
        this.bitmask = bitmask;
        this.encryptType = type;
        this.encryptKey = type.keyType;
        this.macType = macType;
        this.macKey = macType.keyType;
        this.macLen = macLen;
        this.headerLen = magAndVerLen + (encryptKey.keySize >> 3)+ (encryptKey.ivSize >>3) + macLen;
    }

    /**
     * Returns an instance of the SkippingStreamCipher the goes with the current enum value.
     */
    public final SkippingStreamCipher get(){
        return new ChaChaEngine();
    }

    private static final Map<Integer, EncryptedRandomAccessBufferType> byBitmask;
    
    static {
        byBitmask = new HashMap<Integer, EncryptedRandomAccessBufferType>();
        for(EncryptedRandomAccessBufferType type : values())
            byBitmask.put(type.bitmask, type);
    }

    public static EncryptedRandomAccessBufferType getByBitmask(int val) {
        return byBitmask.get(val);
    }

}
