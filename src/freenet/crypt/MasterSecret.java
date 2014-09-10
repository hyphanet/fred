/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.Serializable;
import java.security.InvalidKeyException;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import freenet.support.Logger;

/**
 * MasterSecret is a serializable secret used to derive various keys and ivs for local storage in 
 * Freenet.
 * @author unixninja92
 *
 */
public final class MasterSecret implements Serializable{
    private static final long serialVersionUID = -8411217325990445764L;
    private final SecretKey masterKey;
    
    /**
     * Creates a new MasterSecret. 
     */
    public MasterSecret(){
        masterKey = KeyGenUtils.genSecretKey(KeyType.HMACSHA512);
    }
    
    /**
     * Derives a SecretKey of the specified type from the MasterSecret. 
     * @param type The type of key to derive
     * @return The derived key
     */
    public SecretKey deriveKey(KeyType type){
        try {
            return KeyGenUtils.deriveSecretKey(masterKey, getClass(), type.name()+" key", type);
        } catch (InvalidKeyException e) {
            Logger.error(MasterSecret.class, "Internal error; please report:", e);
        }
        return null;
    }
    
    /**
     * Derives a IvParameterSpec of the specified type from the MasterSecret. 
     * @param type The type of iv to derive
     * @return The derived iv
     */
    public IvParameterSpec deriveIv(KeyType type){
        try {
            return KeyGenUtils.deriveIvParameterSpec(masterKey, getClass(), type.name()+" iv", 
                    type);
        } catch (InvalidKeyException e) {
            Logger.error(MasterSecret.class, "Internal error; please report:", e);
        }
        return null;
    }
}
