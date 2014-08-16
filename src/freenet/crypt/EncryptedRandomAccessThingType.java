package freenet.crypt;

import org.bouncycastle.crypto.SkippingStreamCipher;
import org.bouncycastle.crypto.engines.ChaChaEngine;

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

    private EncryptedRandomAccessThingType(int bitmask, int footLen, CryptByteBufferType type, 
            MACType macType, int macLen){
        this.bitmask = bitmask;
        this.encryptType = type;
        this.encryptKey = type.keyType;
        this.macType = macType;
        this.macKey = macType.keyType;
        this.macLen = macLen;
        this.footerLen = footLen + (encryptKey.keySize >> 3)+ (encryptKey.ivSize >>3) + macLen;
        System.out.println(this.footerLen);
    }

    public final SkippingStreamCipher get(){
        return new ChaChaEngine();
    }

}
