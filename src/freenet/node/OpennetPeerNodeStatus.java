/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

public class OpennetPeerNodeStatus extends PeerNodeStatus {
    public final long timeLastSuccess;

    OpennetPeerNodeStatus(PeerNode peerNode, boolean noHeavy) {
        super(peerNode, noHeavy);
        timeLastSuccess = ((OpennetPeerNode) peerNode).timeLastSuccess();
    }
}
