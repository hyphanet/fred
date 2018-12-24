package freenet.node;

import freenet.io.comm.Peer;

interface ProtectedNodeIPPortDetector extends NodeIPPortDetector {

    Peer[] detectPrimaryPeers();

    void update();

    void startARK();
}
