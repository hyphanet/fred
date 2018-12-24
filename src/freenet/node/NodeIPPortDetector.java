package freenet.node;

import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;

public interface NodeIPPortDetector {
    Peer[] getPrimaryPeers();

    boolean includes(FreenetInetAddress addr);
}
