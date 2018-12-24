package freenet.node;

import freenet.config.Config;
import freenet.config.PersistentConfig;
import freenet.config.SubConfig;
import freenet.io.comm.IOStatisticCollector;
import freenet.io.comm.UdpSocketHandler;
import freenet.io.comm.UdpSocketHandlerImpl;
import freenet.support.SimpleFieldSet;
import junit.framework.TestCase;

import java.net.InetAddress;
import java.net.SocketException;

public class NodeCryptoTest extends TestCase {

    class InstrumentedNodeCryptoImpl extends NodeCryptoImpl {

        int numCreatedSockets = 0;

        public InstrumentedNodeCryptoImpl(final ProtectedNode node, final boolean isOpennet, NodeCryptoConfig config, long startupTime, boolean enableARKs) throws NodeInitException {
            super(node, isOpennet, config, startupTime, enableARKs);
        }

        @Override
        protected UdpSocketHandler newUdpSocketHandler(int listenPort, InetAddress bindto, Node node, long startupTime, String title, IOStatisticCollector collector) throws SocketException {

            // Simulate missing permission to open port lower than 1024
            if (listenPort < 1024) {
                throw new SocketException("Port number below 1024 not permitted.");
            }

            UdpSocketHandler result = new StubUdpSocketHandler();
            numCreatedSockets += 1;
            return result;
        }

        @Override
        protected ProtectedNodeIPPortDetector newNodeIPPortDetector(Node node, NodeIPDetector ipDetector, ProtectedNodeCrypto crypto, boolean enableARKs) {
            return null;
        }
    }

    public void testPortNumbers() {

        StubNode stubNode = new StubNode();

        /**
         * Test if invalid port is detected
         */
        try {
            PersistentConfig testPersistentConfig = new PersistentConfig(new SimpleFieldSet(true));
            Config testConfig = new Config();
            SecurityLevels levels = new SecurityLevels(stubNode, testPersistentConfig);
            SubConfig subConfig = testConfig.createSubConfig("test");
            NodeCryptoConfig cryptoConfig = new NodeCryptoConfig(subConfig, 1, false, levels);

            cryptoConfig.setPort(65536);

            InstrumentedNodeCryptoImpl testNodeCrypto = new InstrumentedNodeCryptoImpl(stubNode, false, cryptoConfig, 0, false);

            assertTrue(false);
        } catch (NodeInitException e) {
            assertTrue(e.exitCode == NodeInitException.EXIT_IMPOSSIBLE_USM_PORT);
        }


        try {
            PersistentConfig testPersistentConfig = new PersistentConfig(new SimpleFieldSet(true));
            Config testConfig = new Config();
            SecurityLevels levels = new SecurityLevels(stubNode, testPersistentConfig);
            SubConfig subConfig = testConfig.createSubConfig("test");
            NodeCryptoConfig cryptoConfig = new NodeCryptoConfig(subConfig, 1, false, levels);

            cryptoConfig.setPort(1023);

            InstrumentedNodeCryptoImpl testNodeCrypto = new InstrumentedNodeCryptoImpl(stubNode, false, cryptoConfig, 0, false);

            assertTrue(false);
        } catch (NodeInitException e) {
            assertTrue(e.exitCode == NodeInitException.EXIT_IMPOSSIBLE_USM_PORT);
        }

        try {
            PersistentConfig testPersistentConfig = new PersistentConfig(new SimpleFieldSet(true));
            Config testConfig = new Config();
            SecurityLevels levels = new SecurityLevels(stubNode, testPersistentConfig);
            SubConfig subConfig = testConfig.createSubConfig("test");
            NodeCryptoConfig cryptoConfig = new NodeCryptoConfig(subConfig, 1, false, levels);

            cryptoConfig.setPort(65535);

            InstrumentedNodeCryptoImpl testNodeCrypto = new InstrumentedNodeCryptoImpl(stubNode, false, cryptoConfig, 0, false);

            assertTrue(true);
        } catch (NodeInitException e) {
            assertTrue(false);
        }

        try {
            PersistentConfig testPersistentConfig = new PersistentConfig(new SimpleFieldSet(true));
            Config testConfig = new Config();
            SecurityLevels levels = new SecurityLevels(stubNode, testPersistentConfig);
            SubConfig subConfig = testConfig.createSubConfig("test");
            NodeCryptoConfig cryptoConfig = new NodeCryptoConfig(subConfig, 1, false, levels);

            cryptoConfig.setPort(1024);

            InstrumentedNodeCryptoImpl testNodeCrypto = new InstrumentedNodeCryptoImpl(stubNode, false, cryptoConfig, 0, false);

            assertTrue(true);
        } catch (NodeInitException e) {
            assertTrue(false);
        }
    }

}
