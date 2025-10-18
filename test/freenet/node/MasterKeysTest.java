package freenet.node;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.MasterSecret;
import org.junit.rules.TemporaryFolder;

public class MasterKeysTest {
    
    @Before
    public void setUp() throws IOException {
        MasterKeys.ITERATE_TIME = 100; // Speed up test.
    }
    
    @Test
    public void testRestartNoPassword() throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        testRestart("");
    }
    
    @Test
    public void testRestartWithPassword() throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        testRestart("password");
    }
    
    private void testRestart(String password) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        File keysFile = new File(temporaryFolder.newFolder(), "test.master.keys");
        DummyRandomSource random = new DummyRandomSource(77391);
        MasterKeys original = MasterKeys.read(keysFile, random, password);
        byte[] clientCacheMasterKey = original.clientCacheMasterKey;
        DatabaseKey dkey = original.createDatabaseKey();
        MasterSecret tempfileMasterSecret = original.getPersistentMasterSecret();
        MasterKeys restored = MasterKeys.read(keysFile, random, password);
        assertArrayEquals(clientCacheMasterKey, restored.clientCacheMasterKey);
        assertEquals(dkey,restored.createDatabaseKey());
        assertEquals(tempfileMasterSecret, restored.getPersistentMasterSecret());
    }
    
    @Test
    public void testChangePasswordEmptyToSomething() throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        testChangePassword("", "password");
    }
    
    @Test
    public void testChangePasswordEmptyToEmpty() throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        testChangePassword("", "");
    }
    
    @Test
    public void testChangePasswordSomethingToEmpty() throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        testChangePassword("password", "");
    }
    
    @Test
    public void testChangePasswordSomethingToSomething() throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        testChangePassword("password", "new password");
    }
    
    private void testChangePassword(String oldPassword, String newPassword) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
        File keysFile = new File(temporaryFolder.newFolder(), "test.master.keys");
        DummyRandomSource random = new DummyRandomSource(77391);
        MasterKeys original = MasterKeys.read(keysFile, random, oldPassword);
        byte[] clientCacheMasterKey = original.clientCacheMasterKey;
        DatabaseKey dkey = original.createDatabaseKey();
        MasterSecret tempfileMasterSecret = original.getPersistentMasterSecret();
        // Change password.
        original.changePassword(keysFile, newPassword, random);
        // Now restore.
        if(!oldPassword.equals(newPassword)) {
            try {
                MasterKeys.read(keysFile, random, oldPassword);
                fail("Old password should not work!");
            } catch (MasterKeysWrongPasswordException e) {
                // Ok.
            }
        }
        MasterKeys restored = MasterKeys.read(keysFile, random, newPassword);
        assertArrayEquals(clientCacheMasterKey, restored.clientCacheMasterKey);
        assertEquals(dkey,restored.createDatabaseKey());
        assertEquals(tempfileMasterSecret, restored.getPersistentMasterSecret());
    }

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

}
