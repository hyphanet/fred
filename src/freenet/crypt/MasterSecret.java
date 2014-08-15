package freenet.crypt;

import java.io.Serializable;
import java.security.InvalidKeyException;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import freenet.support.Logger;

public final class MasterSecret implements Serializable{
    private static final long serialVersionUID = -8411217325990445764L;
    private final SecretKey masterKey;
//    private final MessageAuthCode kdf;
    
    public MasterSecret(){
        masterKey = KeyGenUtils.genSecretKey(KeyType.HMACSHA512);
        MessageAuthCode temp = null;
//        try {
//            temp = new MessageAuthCode(MACType.HMACSHA512, masterKey);
//        } catch (InvalidKeyException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        }
//        kdf = temp;
    }
    
    public SecretKey deriveKey(KeyType type){
        try {
            return KeyGenUtils.deriveSecretKey(masterKey, getClass(), type.name()+" key", type);
        } catch (InvalidKeyException e) {
            Logger.error(MasterSecret.class, "Internal error; please report:", e);
        }
        return null;
    }
    
    public IvParameterSpec deriveIv(int len){
        try {
            return KeyGenUtils.deriveIvParameterSpec(masterKey, getClass(), "iv len "+len, len);
        } catch (InvalidKeyException e) {
            Logger.error(MasterSecret.class, "Internal error; please report:", e);
        }
        return null;
    }
}
