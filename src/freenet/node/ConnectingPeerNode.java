package freenet.node;

import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketManager;

/**
 * A node that we are currently negotiating connection with,
 * which we do not know very much about. If a node comes in from
 * the seednodes, it will probably be a LivePeerNode. If a node
 * connects to us, then it's a ConnectingPeerNode - until we have
 * finished and it becomes a LivePeerNode.
 */
public class ConnectingPeerNode extends PeerNode {

    public ConnectingPeerNode(UdpSocketManager usm, Peer peer, PeerManager pm) {
        super(usm, peer, pm);
    }

    public boolean process(byte[] buf, int offset, int length, Peer peer2) {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isConnected() {
        // If we were connected we'd be a LivePeerNode.
        return false;
    }

}
