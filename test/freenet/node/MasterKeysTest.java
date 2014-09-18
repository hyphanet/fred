package freenet.node;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.MasterSecret;
import freenet.support.io.FileUtil;

public class MasterKeysTest {
    
    private File base = new File("tmp.master-keys-test");
    
    @Before
    public void setUp() {
        FileUtil.removeAll(base);
        base.mkdir();
    }
    
    @After
    public void tearDown() {
        FileUtil.removeAll(base);
    }
    
    @Test
    public void testRestart() throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        testRestart("");
    }
    
    private void testRestart(String password) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        File keysFile = new File(base, "test.master.keys");
        DummyRandomSource random = new DummyRandomSource(77391);
        MasterKeys original = MasterKeys.read(keysFile, random, password);
        byte[] clientCacheMasterKey = original.clientCacheMasterKey;
        DatabaseKey dkey = original.createDatabaseKey(random);
        MasterSecret tempfileMasterSecret = original.getPersistentMasterSecret();
        MasterKeys restored = MasterKeys.read(keysFile, random, password);
        assertArrayEquals(clientCacheMasterKey, restored.clientCacheMasterKey);
        assertEquals(dkey,restored.createDatabaseKey(random));
        assertEquals(tempfileMasterSecret, restored.getPersistentMasterSecret());
    }

}
