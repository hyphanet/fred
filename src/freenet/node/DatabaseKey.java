package freenet.node;

import org.bouncycastle.util.Arrays;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import freenet.crypt.AEADCryptBucket;
import freenet.crypt.HMAC;
import freenet.crypt.RandomSource;
import freenet.support.api.Bucket;

public class DatabaseKey {

    private static final byte[] PLUGIN = "PLUGIN".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLIENT_LAYER = "CLIENT".getBytes(StandardCharsets.UTF_8);
    
    private final byte[] databaseKey;

    /**
     * @param key    key material bytes
     * @param unused randomness source object. Note: this parameter is not used in this object.
     * @deprecated use other constructor {@link #DatabaseKey(byte[])}
     */
    @Deprecated
    DatabaseKey(byte[] key, Random unused) {
        this(key);
    }

    DatabaseKey(byte[] key) {
        this.databaseKey = Arrays.copyOf(key, key.length);
    }
    
    public Bucket createEncryptedBucketForClientLayer(Bucket underlying) {
        return new AEADCryptBucket(underlying, getKeyForClientLayer());
    }

    public static DatabaseKey createRandom(RandomSource random) {
        byte[] databaseKey = new byte[32];
        random.nextBytes(databaseKey);
        return new DatabaseKey(databaseKey);
    }

    /** Key Derivation Function for plugin stores: Use the database key as an HMAC key to an HMAC 
     * of the key plus some constant plus the storeIdentifier.
     * @param storeIdentifier The classname of the plugin, used as part of a filename.
     * @return An encryption key, as byte[].
     */
    public byte[] getPluginStoreKey(String storeIdentifier) {
        byte[] id = storeIdentifier.getBytes(StandardCharsets.UTF_8);
        byte[] full = new byte[databaseKey.length+PLUGIN.length+id.length];
        int x = 0;
        System.arraycopy(databaseKey, 0, full, 0, databaseKey.length);
        x += databaseKey.length;
        System.arraycopy(PLUGIN, 0, full, x, PLUGIN.length);
        x += PLUGIN.length;
        System.arraycopy(id, 0, full, x, id.length);
        return HMAC.macWithSHA256(databaseKey, full);
    }
    
    /** Key Derivation Function for client.dat: Use the database key as an HMAC key to an HMAC 
     * of the key plus some constant plus the storeIdentifier.
     * @return An encryption key, as byte[].
     */
    public byte[] getKeyForClientLayer() {
        byte[] full = new byte[databaseKey.length+CLIENT_LAYER.length];
        int x = 0;
        System.arraycopy(databaseKey, 0, full, 0, databaseKey.length);
        x += databaseKey.length;
        System.arraycopy(CLIENT_LAYER, 0, full, x, CLIENT_LAYER.length);
        return HMAC.macWithSHA256(databaseKey, full);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + java.util.Arrays.hashCode(databaseKey);
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
        DatabaseKey other = (DatabaseKey) obj;
        return java.util.Arrays.equals(databaseKey, other.databaseKey);
    }

}
