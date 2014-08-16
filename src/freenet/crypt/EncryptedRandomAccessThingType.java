package freenet.crypt;

import org.bouncycastle.crypto.SkippingStreamCipher;
import org.bouncycastle.crypto.engines.ChaChaEngine;

public enum EncryptedRandomAccessThingType {
    ChaCha128(1, 52, CryptByteBufferType.ChaCha128, MACType.Poly1305AES, 16),
    ChaCha256(2, 52, CryptByteBufferType.ChaCha256, MACType.Poly1305AES, 16);

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
        this.footerLen = footLen;
        this.encryptType = type;
        this.encryptKey = type.keyType;
        this.macType = macType;
        this.macKey = macType.keyType;
        this.macLen = macLen;
    }

    public final SkippingStreamCipher get(){
        return new ChaChaEngine();
    }

}
