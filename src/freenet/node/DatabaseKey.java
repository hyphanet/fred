package freenet.node;

import java.io.UnsupportedEncodingException;

import org.bouncycastle.util.Arrays;

import com.db4o.io.IoAdapter;

import freenet.crypt.EncryptingIoAdapter;
import freenet.crypt.HMAC;
import freenet.crypt.RandomSource;

public class DatabaseKey {
    
    private final byte[] databaseKey;
    private final RandomSource random;
    
    DatabaseKey(byte[] key, RandomSource random) {
        this.databaseKey = Arrays.copyOf(key, key.length);
        this.random = random;
    }
    
    public EncryptingIoAdapter createEncryptingDb4oAdapter(IoAdapter baseAdapter) {
        return new EncryptingIoAdapter(baseAdapter, databaseKey, random);
    }

    public static DatabaseKey createRandom(RandomSource random) {
        byte[] databaseKey = new byte[32];
        random.nextBytes(databaseKey);
        return new DatabaseKey(databaseKey, random);
    }

    /** Key Derivation Function for plugin stores: Use the database key as an HMAC key to an HMAC 
     * of the key plus some constant plus the storeIdentifier.
     * @param storeIdentifier The classname of the plugin, used as part of a filename.
     * @return An encryption key, as byte[].
     */
    public byte[] getPluginStoreKey(String storeIdentifier) {
        try {
            byte[] id = storeIdentifier.getBytes("UTF-8");
            byte[] full = new byte[databaseKey.length+PLUGIN.length+id.length];
            int x = 0;
            System.arraycopy(databaseKey, 0, full, 0, databaseKey.length);
            x += databaseKey.length;
            System.arraycopy(PLUGIN, 0, full, x, PLUGIN.length);
            x += PLUGIN.length;
            System.arraycopy(id, 0, full, x, id.length);
            return HMAC.macWithSHA256(databaseKey, full, 32);
        } catch (UnsupportedEncodingException e) {
            throw new Error(e);
        }
    }
    
    private static final byte[] PLUGIN;
    
    static {
        try {
            PLUGIN = "PLUGIN".getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new Error(e);
        }
    }

}
