package freenet.node;

import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;
import junit.framework.TestCase;

public class NodeIPPortDetectorTest extends TestCase {

    class InstrumentedIPDetector extends NodeIPDetector {

        public List<ProtectedNodeIPPortDetector> detectors = new LinkedList<ProtectedNodeIPPortDetector>();
        
        public InstrumentedIPDetector(Node node) {
            super(node);
        }
        
        @Override
        public void addPortDetector(ProtectedNodeIPPortDetector detector) {
            detectors.add(detector);
            super.addPortDetector(detector);
        }

    }
    
    class InstrumentedNodeCrypto extends StubNodeCrypto {
        
        @Override
        public FreenetInetAddress getBindTo() {
            
            byte[] IPAddr = new byte[4];
            IPAddr[0] = (byte) 177;
            IPAddr[1] = (byte) 177;
            IPAddr[2] = (byte) 177;
            IPAddr[3] = (byte) 177;
            
            try {
                InetAddress dummyAddress = InetAddress.getByAddress(IPAddr);
                return new FreenetInetAddress(dummyAddress);
            } catch (Exception e) {
                return null;
            }
        }
        
        @Override
        public int getPortNumber() {
            return 13337;
        }
        
    }

    public void testInitializationWithoutARK() {
        Node stubNode = new StubNode();
        ProtectedNodeCrypto stubNodeCrypto = new InstrumentedNodeCrypto();
        InstrumentedIPDetector ipDetector = new InstrumentedIPDetector(stubNode);
        NodeIPPortDetector ipPortDetector = new NodeIPPortDetectorImpl(stubNode, ipDetector, stubNodeCrypto, false);

        assertEquals(1, ipDetector.detectors.size());
    }

    public void testDetectionOfPrimaryAddressWithoutPeers() {
        Node stubNode = new StubNode();
        ProtectedNodeCrypto stubNodeCrypto = new InstrumentedNodeCrypto();
        InstrumentedIPDetector ipDetector = new InstrumentedIPDetector(stubNode);
        NodeIPPortDetector ipPortDetector = new NodeIPPortDetectorImpl(stubNode, ipDetector, stubNodeCrypto, false);
        
        Peer[] detectedIP = ipPortDetector.getPrimaryPeers();
        
        assertEquals(1, detectedIP.length);
    }

}
