package freenet.node;

/** Utility functions used by all transport layer bypass classes */
public class BypassBase {
    
    public BypassBase(Node source, Node target, byte[] sourcePubKeyHash, byte[] targetPubKeyHash) {
        this.sourceNode = source;
        this.targetNode = target;
        this.sourcePubKeyHash = sourcePubKeyHash;
        this.targetPubKeyHash = targetPubKeyHash;
    }
    protected final Node targetNode;
    protected final Node sourceNode;
    private byte[] sourcePubKeyHash;
    private byte[] targetPubKeyHash;
    PeerNode sourcePeerNodeAtTarget;
    PeerNode targetPeerNodeAtSource;

    /** PeerNode on the target node which represents the source node */
    protected synchronized PeerNode getSourcePeerNodeAtTarget() {
        if(sourcePeerNodeAtTarget != null) return sourcePeerNodeAtTarget;
        sourcePeerNodeAtTarget = targetNode.peers.getByPubKeyHash(sourcePubKeyHash);
        return sourcePeerNodeAtTarget;
    }

    /** PeerNode on the source node which represents the target node */
    protected synchronized PeerNode getTargetPeerNodeAtSource() {
        if(targetPeerNodeAtSource != null) return targetPeerNodeAtSource;
        targetPeerNodeAtSource = sourceNode.peers.getByPubKeyHash(targetPubKeyHash);
        return targetPeerNodeAtSource;
    }

}
