/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.Serializable;
import java.security.InvalidKeyException;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

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
    
    public MasterSecret(byte[] secret) {
        if(secret.length != 64) throw new IllegalArgumentException();
        masterKey = KeyGenUtils.getSecretKey(KeyType.HMACSHA512, secret);
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
            throw new IllegalStateException(e); // Definitely a bug.
        }
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
            throw new IllegalStateException(e); // Definitely a bug.
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((masterKey == null) ? 0 : masterKey.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        MasterSecret other = (MasterSecret) obj;
        return masterKey.equals(other.masterKey);
    }
}
