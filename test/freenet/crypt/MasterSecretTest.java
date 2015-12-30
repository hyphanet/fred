/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import static org.junit.Assert.*;

import org.junit.Test;

public class MasterSecretTest {
    private final static KeyType[] types = KeyType.values();
    
    @Test
    public void testDeriveKey() {
        MasterSecret secret = new MasterSecret();
        for(KeyType type: types){
            assertNotNull(secret.deriveKey(type));
        }
    }

    @Test
    public void testDeriveKeyLength() {
        MasterSecret secret = new MasterSecret();
        for(KeyType type: types){
            assertEquals(secret.deriveKey(type).getEncoded().length, type.keySize >> 3);
        }
    }
    

    @Test (expected = NullPointerException.class)
    public void testDeriveKeyNullInput() {
        MasterSecret secret = new MasterSecret();
        secret.deriveKey(null);
    }
    
    @Test
    public void testDeriveIv() {
        MasterSecret secret = new MasterSecret();
        for(KeyType type: types){
            assertNotNull(secret.deriveIv(type));
        }
    }

    @Test
    public void testDeriveIvLength() {
        MasterSecret secret = new MasterSecret();
        for(KeyType type: types){
            assertEquals(secret.deriveIv(type).getIV().length, type.ivSize >> 3);
        }
    }
    

    @Test (expected = NullPointerException.class)
    public void testDeriveIvNullInput() {
        MasterSecret secret = new MasterSecret();
        secret.deriveIv(null);
    }

}
