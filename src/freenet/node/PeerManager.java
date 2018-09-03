package freenet.node;

import freenet.io.comm.*;
import freenet.keys.Key;
import freenet.support.ByteArrayWrapper;
import freenet.support.SimpleFieldSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public interface PeerManager {

    // We can't trust our strangers, so need a consensus.
    int OUTDATED_MIN_TOO_NEW_TOTAL = 5;
    // We can trust our friends, so only 1 is needed.
    int OUTDATED_MIN_TOO_NEW_DARKNET = 1;
    int OUTDATED_MAX_CONNS = 5;

    int PEER_NODE_STATUS_CONNECTED = 1;
    int PEER_NODE_STATUS_ROUTING_BACKED_OFF = 2;
    int PEER_NODE_STATUS_TOO_NEW = 3;
    int PEER_NODE_STATUS_TOO_OLD = 4;
    int PEER_NODE_STATUS_DISCONNECTED = 5;
    int PEER_NODE_STATUS_NEVER_CONNECTED = 6;
    int PEER_NODE_STATUS_DISABLED = 7;
    int PEER_NODE_STATUS_BURSTING = 8;
    int PEER_NODE_STATUS_LISTENING = 9;
    int PEER_NODE_STATUS_LISTEN_ONLY = 10;
    int PEER_NODE_STATUS_CLOCK_PROBLEM = 11;
    int PEER_NODE_STATUS_CONN_ERROR = 12;
    int PEER_NODE_STATUS_DISCONNECTING = 13;
    int PEER_NODE_STATUS_ROUTING_DISABLED = 14;
    int PEER_NODE_STATUS_NO_LOAD_STATS = 15;
    
    boolean addPeer(PeerNode pn);

    boolean removeAllPeers();

    boolean disconnected(PeerNode pn);

    void addConnectedPeer(PeerNode pn);

    PeerNode getByPeer(Peer peer);

    PeerNode getByPeer(Peer peer, FNPPacketMangler mangler);

    ArrayList<PeerNode> getAllConnectedByAddress(FreenetInetAddress a, boolean strict);

    void connect(SimpleFieldSet noderef, OutgoingPacketMangler mangler, DarknetPeerNode.FRIEND_TRUST trust, DarknetPeerNode.FRIEND_VISIBILITY visibility) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException;

    void disconnectAndRemove(PeerNode pn, boolean sendDisconnectMessage, boolean waitForAck, boolean purge);

    void disconnect(PeerNode pn, boolean sendDisconnectMessage, boolean waitForAck, boolean purge, boolean dumpMessagesNow, boolean remove, long timeout);

    double[] getPeerLocationDoubles(boolean pruneBackedOffPeers);

    PeerNode getRandomPeer(PeerNode exclude);

    void localBroadcast(Message msg, boolean ignoreRoutability,
                        boolean onlyRealConnections, ByteCounter ctr);

    void localBroadcast(Message msg, boolean ignoreRoutability,
                        boolean onlyRealConnections, ByteCounter ctr, int minVersion, int maxVersion);

    void locallyBroadcastDiffNodeRef(SimpleFieldSet fs, boolean toDarknetOnly, boolean toOpennetOnly);

    PeerNode getRandomPeer();

    PeerNode closerPeer(PeerNode pn, Set<PeerNode> routedTo, double loc, boolean ignoreSelf, boolean calculateMisrouting,
                        int minVersion, List<Double> addUnpickedLocsTo, Key key, short outgoingHTL, long ignoreBackoffUnder, boolean isLocal, boolean realTime, boolean excludeMandatoryBackoff);

    PeerNode closerPeer(PeerNode pn, Set<PeerNode> routedTo, double target, boolean ignoreSelf,
                        boolean calculateMisrouting, int minVersion, List<Double> addUnpickedLocsTo, double maxDistance, Key key, short outgoingHTL, long ignoreBackoffUnder, boolean isLocal, boolean realTime,
                        RecentlyFailedReturn recentlyFailed, boolean ignoreTimeout, long now, boolean newLoadManagement);

    String getStatus();

    String getTMCIPeerList();

    void updatePMUserAlert();

    boolean anyConnectedPeers();

    boolean anyDarknetPeers();

    void readExtraPeerData();

    void start();

    int countNonBackedOffPeers(boolean realTime);

    void maybeUpdateOldestNeverConnectedDarknetPeerAge(long now);

    long getOldestNeverConnectedDarknetPeerAge();

    void maybeLogPeerNodeStatusSummary(long now);

    void changePeerNodeStatus(PeerNode peerNode, int oldPeerNodeStatus,
                              int peerNodeStatus, boolean noLog);

    int getPeerNodeStatusSize(int pnStatus, boolean darknet);

    void addPeerNodeRoutingBackoffReason(String peerNodeRoutingBackoffReason, PeerNode peerNode, boolean realTime);

    String[] getPeerNodeRoutingBackoffReasons(boolean realTime);

    int getPeerNodeRoutingBackoffReasonSize(String peerNodeRoutingBackoffReason, boolean realTime);

    void removePeerNodeRoutingBackoffReason(String peerNodeRoutingBackoffReason, PeerNode peerNode, boolean realTime);

    PeerNodeStatus[] getPeerNodeStatuses(boolean noHeavy);

    DarknetPeerNodeStatus[] getDarknetPeerNodeStatuses(boolean noHeavy);

    OpennetPeerNodeStatus[] getOpennetPeerNodeStatuses(boolean noHeavy);

    void maybeUpdatePeerNodeRoutableConnectionStats(long now);

    DarknetPeerNode[] getDarknetPeers();

    List<SeedServerPeerNode> getConnectedSeedServerPeersVector(HashSet<ByteArrayWrapper> exclude);

    List<SeedServerPeerNode> getSeedServerPeersVector();

    OpennetPeerNode[] getOpennetPeers();

    PeerNode[] getOpennetAndSeedServerPeers();

    boolean anyConnectedPeerHasAddress(FreenetInetAddress addr, PeerNode pn);

    void removeOpennetPeers();

    PeerNode containsPeer(PeerNode pn);

    int countConnectedDarknetPeers();

    int countConnectedPeers();

    int countAlmostConnectedDarknetPeers();

    int countCompatibleDarknetPeers();

    int countCompatibleRealPeers();

    int countConnectedOpennetPeers();

    int countValidPeers();

    int countConnectiblePeers();

    int countSeednodes();

    int countBackedOffPeers(boolean realTime);

    PeerNode getByPubKeyHash(byte[] pkHash);

    void addPeerStatusChangeListener(PeerStatusChangeListener listener);

    void removePeerStatusChangeListener(PeerStatusChangeListener listener);

    int countByStatus(int status);

    boolean isOutdated();
}
