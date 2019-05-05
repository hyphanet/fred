/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package freenet.node;

import freenet.config.Config;
import freenet.config.PersistentConfig;
import freenet.config.SubConfig;
import freenet.support.SimpleFieldSet;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import junit.framework.TestCase;

public class OpennetManagerTest extends TestCase {
    
    private File writeOpennetConfig(SimpleFieldSet config, String prefix) throws IOException {

        File outputFile = File.createTempFile(prefix, ".tmp");

        FileOutputStream fos = null;
        OutputStreamWriter osr = null;
        BufferedWriter bw = null;
        fos = new FileOutputStream(outputFile);
        osr = new OutputStreamWriter(fos, "UTF-8");
        bw = new BufferedWriter(osr);
        config.writeTo(bw);

        bw.close();
        
        return outputFile;
    }
    
    public void testInitialization() {
        
        class TestNodeCrypto extends StubNodeCrypto {

            int mPortNumber = 0;
            SimpleFieldSet mConfig = null;
            boolean mInitCryptoCalled = false;
            
            public void setPortNumber(int port) {
                mPortNumber = port;
            }
            
            @Override
            public int getPortNumber() {
                return mPortNumber;
            }
            
            @Override
            public void readCrypto(SimpleFieldSet config) {
                mConfig = config;
            }
            
            @Override
            public void initCrypto() {
                mInitCryptoCalled = true;
            }
        }

        class TestProgramDirectory extends ProgramDirectory {
            
            Map<String, File> testFiles = new HashMap<>();
            Set<String> readFiles = new HashSet<>();
            
            public void setFile(String path, File file) {
                testFiles.put(path, file);
            }
            
            @Override
            public File file(String base) {
                File result = new File("");
                if (testFiles.containsKey(base)) {
                    result = testFiles.get(base);
                }
                System.out.println("Reading file " + base);
                readFiles.add(base);
                return result;
            }
            
        }
        
        class TestNode extends StubNode {
            
            TestProgramDirectory testDir = null;
            
            public void setProgramDirectory(TestProgramDirectory dir) {
                testDir = dir;
            }
            
            @Override
            public ProgramDirectory nodeDir() {
                    
                return testDir;
                
            }
            
        }
        
        class InstrumentedOpennetManager extends OpennetManager {

            public InstrumentedOpennetManager(ProtectedNode node, NodeCryptoConfig opennetConfig, long startupTime, boolean enableAnnouncement) throws NodeInitException {
                super(node, opennetConfig, startupTime, enableAnnouncement);
            }

            @Override
            protected ProtectedNodeCrypto newNodeCrypto(final ProtectedNode node, final boolean isOpennet, NodeCryptoConfig config, long startupTime, boolean enableARKs) throws NodeInitException {
                TestNodeCrypto result = new TestNodeCrypto();
                result.setPortNumber(config.getPort());
                return result;
            }
        }
        
        TestNode node = new TestNode();
        PersistentConfig testPersistentConfig = new PersistentConfig(new SimpleFieldSet(true));
        Config testConfig = new Config();
        SecurityLevels levels = new SecurityLevels(node, testPersistentConfig);
        SubConfig subConfig = testConfig.createSubConfig("test");
        
        try {
        
        // Create node crypto config
        NodeCryptoConfig cryptoConfig = new NodeCryptoConfig(subConfig, 1, false, levels);
        cryptoConfig.setPort(12000);
        
        /**
         * Test reading of primary config file
         */
        
        // Create opennet config
        SimpleFieldSet opennetConfig = new SimpleFieldSet(true);
        opennetConfig.put("test", true);
        File opennetConfigFile = writeOpennetConfig(opennetConfig, "opennetmanagertest-initializationwithoutannouncement");
        TestProgramDirectory testDir = new TestProgramDirectory();
        testDir.setFile("opennet-12000", opennetConfigFile);
        node.setProgramDirectory(testDir);
        
        OpennetManager oManager = new InstrumentedOpennetManager(node, cryptoConfig, 0, false);
        // We expect the opennetmanager to 
        // (1) initialize node crypto
        assertNotNull(oManager.crypto);
        assertFalse(((TestNodeCrypto) oManager.crypto).mInitCryptoCalled);
        assertTrue(((TestNodeCrypto) oManager.crypto).mConfig.getBoolean("test"));
        // (2) process the node file
        assertTrue(testDir.readFiles.contains("opennet-12000"));
        assertTrue(testDir.readFiles.contains("opennet-12000.bak"));
        // (3) not initialize the announcer
        assertEquals(null, oManager.announcer);
        
        /**
         * Test enabling of announcer
         */
        oManager = new InstrumentedOpennetManager(node, cryptoConfig, 0, true);
        assertNotNull(oManager.announcer);
        
        opennetConfigFile.delete();
        
        /**
         * Test fallback to backup file
         */
        node = new TestNode();
        opennetConfig = new SimpleFieldSet(true);
        opennetConfig.put("test2", true);
        opennetConfigFile = writeOpennetConfig(opennetConfig, "opennetmanagertest-initializationwithoutannouncement");
        testDir = new TestProgramDirectory();
        testDir.setFile("opennet-12000.bak", opennetConfigFile);
        node.setProgramDirectory(testDir);

        oManager = new InstrumentedOpennetManager(node, cryptoConfig, 0, false);
        assertNotNull(oManager.crypto);
        assertFalse(((TestNodeCrypto) oManager.crypto).mInitCryptoCalled);
        assertTrue(((TestNodeCrypto) oManager.crypto).mConfig.getBoolean("test2"));
       
        opennetConfigFile.delete();
        
        /**
         * Test fallback to new setup
         */
        node = new TestNode();
        testDir = new TestProgramDirectory();
        node.setProgramDirectory(testDir);
        oManager = new InstrumentedOpennetManager(node, cryptoConfig, 0, false);
        assertNotNull(oManager.crypto);
        assertTrue(((TestNodeCrypto) oManager.crypto).mInitCryptoCalled);
        
        } catch (Exception e) {
            e.printStackTrace();
            assertFalse(true);
            
        }

        
    }
    
}
