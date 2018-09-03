package freenet.node;

import freenet.io.comm.ByteCounter;

/**
 * This package-local interface extends the PeerManager interface with
 * procedures that shall only be accessible to classes in the same package
 * as the peer manager implementation.
 */
interface ProtectedPeerManager extends PeerManager {

    long getTimeFirstAnyConnections();

    void tryReadPeers(String filename, NodeCrypto crypto, OpennetManager opennet, boolean isOpennet, boolean oldOpennetPeers);

    boolean addPeer(PeerNode pn, boolean ignoreOpennet, boolean reactivate);

    PeerNode[] myPeers();

    PeerNode[] connectedPeers();

    void writePeers(boolean opennet);

    void writePeersUrgent(boolean opennet);

    void writePeersDarknetUrgent();

    void writePeersDarknet();

    void writePeersOpennet();

    boolean havePeer(PeerNode pn);

    ByteCounter getDisconnectionCounter();

    void incrementSelectionSamples(long now, PeerNode pn);
}
