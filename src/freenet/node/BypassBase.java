package freenet.node;

import freenet.crypt.BlockCipher;
import freenet.io.comm.Peer;

/** Utility functions used by all transport layer bypass classes */
public class BypassBase {
    
    public BypassBase(Node source, Node target, NodeCrypto sourceCrypto, NodeCrypto targetCrypto) {
        this.sourceNode = source;
        this.targetNode = target;
        this.sourceCrypto = sourceCrypto;
        this.targetCrypto = targetCrypto;
        this.sourcePubKeyHash = sourceCrypto.ecdsaPubKeyHash;
        this.targetPubKeyHash = targetCrypto.ecdsaPubKeyHash;
    }
    
    protected final Node targetNode;
    protected final Node sourceNode;
    protected final NodeCrypto sourceCrypto;
    protected final NodeCrypto targetCrypto;
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

    /** Falsify a connection by calling the appropriate methods on PeerNode on both sides. 
     * Should be called by a simulation or a multi-node-one-VM setup after both nodes are known
     * to be running. */
    public void fakeConnect() {
        getSourcePeerNodeAtTarget();
        getTargetPeerNodeAtSource();
        if(sourcePeerNodeAtTarget.isConnected()) return;
        long targetBootID = sourcePeerNodeAtTarget.getBootID();
        long sourceBootID = targetPeerNodeAtSource.getBootID();
        byte[] sourceRef = sourceCrypto.myCompressedSetupRef();
        byte[] targetRef = targetCrypto.myCompressedFullRef();
        Peer sourcePeer = sourcePeerNodeAtTarget.getPeer();
        Peer targetPeer = targetPeerNodeAtSource.getPeer();
        int[] negTypes = sourceCrypto.packetMangler.supportedNegTypes(true);
        int negType = negTypes[negTypes.length-1];
        
        sourcePeerNodeAtTarget.completedHandshake(sourceBootID, sourceRef, 0, sourceRef.length, 
                dummyCipher, randomKey(), dummyCipher, randomKey(), sourcePeer, true, negType, 
                -1, false, false, randomKey(), dummyCipher, randomKey(), 0, 0, 0, 0);
        targetPeerNodeAtSource.completedHandshake(targetBootID, targetRef, 0, targetRef.length, 
                dummyCipher, randomKey(), dummyCipher, randomKey(), targetPeer, false, negType, 
                -1, true, false, randomKey(), dummyCipher, randomKey(), 0, 0, 0, 0);
        
        sourcePeerNodeAtTarget.verified(sourcePeerNodeAtTarget.getUnverifiedKeyTracker());
        sourcePeerNodeAtTarget.maybeSendInitialMessages();
        targetPeerNodeAtSource.maybeSendInitialMessages();
        assert(sourcePeerNodeAtTarget.isConnected());
        assert(targetPeerNodeAtSource.isConnected());
    }
    
    private final byte[] randomKey() {
        byte[] buf = new byte[32];
        targetNode.fastWeakRandom.nextBytes(buf);
        return buf;
    }
    
    static class ThrowingDummyBlockCipher implements BlockCipher {

        @Override
        public void initialize(byte[] key) {
            // Ignore.
        }

        @Override
        public int getKeySize() {
            return 32;
        }

        @Override
        public int getBlockSize() {
            return 32;
        }

        @Override
        public void encipher(byte[] block, byte[] result) {
            throw new IllegalStateException("ThrowingDummyBlockCipher should not be used for " +
                    "actual encryption!");
        }

        @Override
        public void decipher(byte[] block, byte[] result) {
            throw new IllegalStateException("ThrowingDummyBlockCipher should not be used for " +
                    "actual decryption!");
        }
        
    }
    
    static final ThrowingDummyBlockCipher dummyCipher = new ThrowingDummyBlockCipher();

}
