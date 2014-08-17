/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import org.bouncycastle.crypto.SkippingStreamCipher;
import org.bouncycastle.crypto.engines.ChaChaEngine;

/**
 * 
 * @author unixninja92
 *
 */
public enum EncryptedRandomAccessThingType {
    ChaCha128(1, 12, CryptByteBufferType.ChaCha128, MACType.HMACSHA256, 32),
    ChaCha256(2, 12, CryptByteBufferType.ChaCha256, MACType.HMACSHA256, 32);

    public final int bitmask;
    public final int footerLen;//bytes
    public final CryptByteBufferType encryptType;
    public final KeyType encryptKey;
    public final MACType macType;
    public final KeyType macKey;
    public final int macLen;//bytes

    /**
     * 
     * @param bitmask
     * @param magAndVerLen Length of magic value and version
     * @param type
     * @param macType
     * @param macLen
     */
    private EncryptedRandomAccessThingType(int bitmask, int magAndVerLen, CryptByteBufferType type, 
            MACType macType, int macLen){
        this.bitmask = bitmask;
        this.encryptType = type;
        this.encryptKey = type.keyType;
        this.macType = macType;
        this.macKey = macType.keyType;
        this.macLen = macLen;
        this.footerLen = magAndVerLen + (encryptKey.keySize >> 3)+ (encryptKey.ivSize >>3) + macLen;
    }

    /**
     * 
     * @return
     */
    public final SkippingStreamCipher get(){
        return new ChaChaEngine();
    }

}
