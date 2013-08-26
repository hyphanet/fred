package freenet.node;

import org.bouncycastle.util.Arrays;

import com.db4o.io.IoAdapter;

import freenet.crypt.EncryptingIoAdapter;
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

}
