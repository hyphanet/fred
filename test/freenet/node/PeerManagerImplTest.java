package freenet.node;

import junit.framework.TestCase;

public class PeerManagerImplTest extends TestCase {

    class InstrumentedPeerManager extends PeerManagerImpl {

        public InstrumentedPeerManager(ProtectedNode node, SemiOrderedShutdownHook shutdownHook) {
            super(node, shutdownHook);
        }

    }

    public void testInitialization() {

        SemiOrderedShutdownHook hook = SemiOrderedShutdownHook.get();
        StubNode stubNode = new StubNode();
        InstrumentedPeerManager peerManager = new InstrumentedPeerManager(stubNode, hook);

        assertEquals(0, peerManager.myPeers().length);
        assertEquals(0, peerManager.connectedPeers().length);
    }

}
