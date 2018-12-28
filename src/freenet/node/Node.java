package freenet.node;

import freenet.client.FetchContext;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.PersistentConfig;
import freenet.config.SubConfig;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.RandomSource;
import freenet.io.comm.*;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.*;
import freenet.node.probe.Listener;
import freenet.node.probe.Type;
import freenet.node.stats.DataStoreInstanceType;
import freenet.node.stats.DataStoreStats;
import freenet.node.updater.NodeUpdateManager;
import freenet.pluginmanager.ForwardPort;
import freenet.pluginmanager.PluginManager;
import freenet.store.BlockMetadata;
import freenet.store.KeyCollisionException;
import freenet.store.StorableBlock;
import freenet.store.StoreCallback;
import freenet.support.*;
import freenet.support.math.MersenneTwister;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public interface Node {

    /** The number of bytes per key total in all the different datastores. All the datastores
     * are always the same size in number of keys. */
    int sizePerKey = CHKBlock.DATA_LENGTH + CHKBlock.TOTAL_HEADERS_LENGTH +
        DSAPublicKey.PADDED_SIZE + SSKBlock.DATA_LENGTH + SSKBlock.TOTAL_HEADERS_LENGTH;

    short DEFAULT_MAX_HTL = (short)18;

    /**
     * Minimum uptime for us to consider a node an acceptable place to store a key. We store a key
     * to the datastore only if it's from an insert, and we are a sink, but when calculating whether
     * we are a sink we ignore nodes which have less uptime (percentage) than this parameter.
     */
    int MIN_UPTIME_STORE_KEY = 40;

    int PACKETS_IN_BLOCK = 32;
    int PACKET_SIZE = 1024;
    double DECREMENT_AT_MIN_PROB = 0.25;
    double DECREMENT_AT_MAX_PROB = 0.5;
    // Send keepalives every 7-14 seconds. Will be acked and if necessary resent.
    // Old behaviour was keepalives every 14-28. Even that was adequate for a 30 second
    // timeout. Most nodes don't need to send keepalives because they are constantly busy,
    // this is only an issue for disabled darknet connections, very quiet private networks
    // etc.
    long KEEPALIVE_INTERVAL = SECONDS.toMillis(7);
    // If no activity for 30 seconds, node is dead
    // 35 seconds allows plenty of time for resends etc even if above is 14 sec as it is on older nodes.
    long MAX_PEER_INACTIVITY = SECONDS.toMillis(35);
    /** Time after which a handshake is assumed to have failed. */
    int HANDSHAKE_TIMEOUT = (int) MILLISECONDS.toMillis(4800); // Keep the below within the 30 second assumed timeout.
    // Inter-handshake time must be at least 2x handshake timeout
    int MIN_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT*2; // 10-20 secs
    int RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT*2; // avoid overlap when the two handshakes are at the same time
    int MIN_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*4;
    int RANDOMIZED_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*2; // 20-30 secs
    int MIN_TIME_BETWEEN_VERSION_SENDS = HANDSHAKE_TIMEOUT*4;
    int RANDOMIZED_TIME_BETWEEN_VERSION_SENDS = HANDSHAKE_TIMEOUT*2; // 20-30 secs
    int MIN_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS = HANDSHAKE_TIMEOUT*24; // 2-5 minutes
    int RANDOMIZED_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS = HANDSHAKE_TIMEOUT*36;
    int MIN_BURSTING_HANDSHAKE_BURST_SIZE = 1; // 1-4 handshake sends per burst
    int RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE = 3;
    // If we don't receive any packets at all in this period, from any node, tell the user
    long ALARM_TIME = MINUTES.toMillis(1);

    long MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS = MILLISECONDS.toMillis(900);
    long MIN_INTERVAL_BETWEEN_INCOMING_PROBE_REQUESTS = MILLISECONDS.toMillis(1000);
    int SYMMETRIC_KEY_LENGTH = 32; // 256 bits - note that this isn't used everywhere to determine it
    
    /** Should inserts ignore low backoff times by default? */
    boolean IGNORE_LOW_BACKOFF_DEFAULT = false;
    /** Definition of "low backoff times" for above. */
    long LOW_BACKOFF = SECONDS.toMillis(30);
    /** Should inserts be fairly blatently prioritised on accept by default? */
    boolean PREFER_INSERT_DEFAULT = false;
    /** Should inserts fork when the HTL reaches cacheability? */
    boolean FORK_ON_CACHEABLE_DEFAULT = true;
    /** Type identifier for fproxy node to node messages, as sent on DMT.nodeToNodeMessage's */
    int N2N_MESSAGE_TYPE_FPROXY = 1;
    /** Type identifier for differential node reference messages, as sent on DMT.nodeToNodeMessage's */
    int N2N_MESSAGE_TYPE_DIFFNODEREF = 2;
    /** Identifier within fproxy messages for simple, short text messages to be displayed on the homepage as useralerts */
    int N2N_TEXT_MESSAGE_TYPE_USERALERT = 1;
    /** Identifier within fproxy messages for an offer to transfer a file */
    int N2N_TEXT_MESSAGE_TYPE_FILE_OFFER = 2;
    /** Identifier within fproxy messages for accepting an offer to transfer a file */
    int N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_ACCEPTED = 3;
    /** Identifier within fproxy messages for rejecting an offer to transfer a file */
    int N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_REJECTED = 4;
    /** Identified within friend feed for the recommendation of a bookmark */
    int N2N_TEXT_MESSAGE_TYPE_BOOKMARK = 5;
    /** Identified within friend feed for the recommendation of a file */
    int N2N_TEXT_MESSAGE_TYPE_DOWNLOAD = 6;
    int EXTRA_PEER_DATA_TYPE_N2NTM = 1;
    int EXTRA_PEER_DATA_TYPE_PEER_NOTE = 2;
    int EXTRA_PEER_DATA_TYPE_QUEUED_TO_SEND_N2NM = 3;
    int EXTRA_PEER_DATA_TYPE_BOOKMARK = 4;
    int EXTRA_PEER_DATA_TYPE_DOWNLOAD = 5;
    int PEER_NOTE_TYPE_PRIVATE_DARKNET_COMMENT = 1;

    /**
     * Minimum bandwidth limit in bytes considered usable: 10 KiB. If there is an attempt to set a limit below this -
     * excluding the reserved -1 for input bandwidth - the callback will throw. See the callbacks for
     * outputBandwidthLimit and inputBandwidthLimit. 10 KiB are equivalent to 50 GiB traffic per month.
     */
    int minimumBandwidth = 10 * 1024;

    boolean isTestnetEnabled();

    <T extends StorableBlock> void closeOldStore(StoreCallback<T> old);

    TrafficClass getTrafficClass();

    void startProbe(byte htl, long uid, Type type, Listener listener);

    void makeStore(String val) throws InvalidConfigValueException;

    void writeNodeFile();

    void writeOpennetFile();

    boolean isUsingWrapper();

    NodeStarter getNodeStarter();

    ProgramDirectory setupProgramDir(SubConfig installConfig,
                                     String cfgKey, String defaultValue, String shortdesc, String longdesc, String moveErrMsg,
                                     SubConfig oldConfig) throws NodeInitException;

    void lateSetupDatabase(DatabaseKey databaseKey) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException;

    void killMasterKeysFile() throws IOException;

    void start(boolean noSwaps) throws NodeInitException;

    SimpleFieldSet exportVolatileFieldSet();

    int routedPing(double loc2, byte[] pubKeyHash);

    Object makeRequestSender(Key key, short htl, long uid, RequestTag tag, PeerNode source, boolean localOnly, boolean ignoreStore, boolean offersOnly, boolean canReadClientCache, boolean canWriteClientCache, boolean realTimeFlag);

    KeyBlock fetch(Key key, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, BlockMetadata meta);

    SSKBlock fetch(NodeSSK key, boolean dontPromote, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, BlockMetadata meta);

    CHKBlock fetch(NodeCHK key, boolean dontPromote, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, BlockMetadata meta);

    Map<DataStoreInstanceType, DataStoreStats> getDataStoreStats();

    long getMaxTotalKeys();

    void dumpStoreHits();

    void storeShallow(CHKBlock block, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR);

    void store(KeyBlock block, boolean deep, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR) throws KeyCollisionException;

    void storeInsert(SSKBlock block, boolean deep, boolean overwrite, boolean canWriteClientCache, boolean canWriteDatastore) throws KeyCollisionException;

    void storeShallow(SSKBlock block, boolean canWriteClientCache, boolean canWriteDatastore, boolean fromULPR) throws KeyCollisionException;

    void store(SSKBlock block, boolean deep, boolean overwrite, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR) throws KeyCollisionException;

    short decrementHTL(PeerNode source, short htl);

    CHKInsertSender makeInsertSender(NodeCHK key, short htl, long uid, InsertTag tag, PeerNode source,
                                     byte[] headers, PartiallyReceivedBlock prb, boolean fromStore, boolean canWriteClientCache, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff, boolean realTimeFlag);

    SSKInsertSender makeInsertSender(SSKBlock block, short htl, long uid, InsertTag tag, PeerNode source,
                                     boolean fromStore, boolean canWriteClientCache, boolean canWriteDatastore, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff, boolean realTimeFlag);

    String getStatus();

    String getTMCIPeerList();

    ClientKeyBlock fetchKey(ClientKey key, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore) throws KeyVerifyException;

    ClientKeyBlock fetch(ClientSSK clientSSK, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore) throws SSKVerifyException;

    void exit(int reason);

    void exit(String reason);

    boolean isStopping();

    void park();

    NodeUpdateManager getNodeUpdater();

    DarknetPeerNode[] getDarknetConnections();

    boolean addPeerConnection(PeerNode pn);

    void removePeerConnection(PeerNode pn);

    void onConnectedPeer();

    int getFNPPort();

    boolean isOudated();

    void registerNodeToNodeMessageListener(int type, NodeToNodeMessageListener listener);

    void receivedNodeToNodeMessage(Message m, PeerNode src);

    void receivedNodeToNodeMessage(PeerNode src, int type, ShortBuffer messageData, boolean partingMessage);

    void handleNodeToNodeTextMessageSimpleFieldSet(SimpleFieldSet fs, DarknetPeerNode source, int fileNumber) throws FSParseException;

    String getMyName();

    MessageCore getUSM();

    LocationManager getLocationManager();

    PeerNode[] getPeerNodes();

    PeerNode[] getConnectedPeers();

    PeerNode getPeerNode(String nodeIdentifier);

    boolean isHasStarted();

    void queueRandomReinsert(KeyBlock block);

    String getExtraPeerDataDir();

    boolean noConnectedPeers();

    double getLocation();

    double getLocationChangeSession();

    int getAverageOutgoingSwapTime();

    long getSendSwapInterval();

    boolean isAdvancedModeEnabled();

    boolean isFProxyJavascriptEnabled();

    int getNumARKFetchers();

    void sentPayload(int len);

    long getTotalPayloadSent();

    void setName(String key) throws InvalidConfigValueException, NodeNeedRestartException;

    Ticker getTicker();

    int getUnclaimedFIFOSize();

    void connectToSeednode(SeedServerTestPeerNode node) throws OpennetDisabledException, FSParseException, PeerParseException, ReferenceSignatureVerificationException;

    void connect(Node node, DarknetPeerNode.FRIEND_TRUST trust, DarknetPeerNode.FRIEND_VISIBILITY visibility) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException;

    short maxHTL();

    int getDarknetPortNumber();

    int getOutputBandwidthLimit();

    int getInputBandwidthLimit();

    long getStoreSize();

    void setTimeSkewDetectedUserAlert();

    File getNodeDir();

    File getCfgDir();

    File getUserDir();

    File getRunDir();

    File getStoreDir();

    File getPluginDir();

    ProgramDirectory nodeDir();

    ProgramDirectory cfgDir();

    ProgramDirectory userDir();

    ProgramDirectory runDir();

    ProgramDirectory storeDir();

    ProgramDirectory pluginDir();

    DarknetPeerNode createNewDarknetNode(SimpleFieldSet fs, DarknetPeerNode.FRIEND_TRUST trust, DarknetPeerNode.FRIEND_VISIBILITY visibility) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException;

    OpennetPeerNode createNewOpennetNode(SimpleFieldSet fs) throws FSParseException, OpennetDisabledException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException;

    SeedServerTestPeerNode createNewSeedServerTestPeerNode(SimpleFieldSet fs) throws FSParseException, OpennetDisabledException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException;

    OpennetPeerNode addNewOpennetNode(SimpleFieldSet fs, OpennetManager.ConnectionType connectionType) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException;

    byte[] getOpennetPubKeyHash();

    byte[] getDarknetPubKeyHash();

    boolean isOpennetEnabled();

    SimpleFieldSet exportDarknetPublicFieldSet();

    SimpleFieldSet exportOpennetPublicFieldSet();

    SimpleFieldSet exportDarknetPrivateFieldSet();

    SimpleFieldSet exportOpennetPrivateFieldSet();

    boolean dontDetect();

    int getOpennetFNPPort();

    OpennetManager getOpennet();

    boolean passOpennetRefsThroughDarknet();

    Set<ForwardPort> getPublicInterfacePorts();

    long getUptime();

    UdpSocketHandler[] getPacketSocketHandlers();

    int getMaxOpennetPeers();

    void onAddedValidIP();

    boolean isSeednode();

    boolean wantAnonAuth(boolean isOpennet);

    // FIXME make this configurable
    // Probably should wait until we have non-opennet anon auth so we can add it to NodeCrypto.
    boolean wantAnonAuthChangeIP(boolean isOpennet);

    boolean opennetDefinitelyPortForwarded();

    boolean darknetDefinitelyPortForwarded();

    boolean hasKey(Key key, boolean canReadClientCache, boolean forULPR);

    void setLocation(double loc);

    boolean peersWantKey(Key key);

    void setDispatcherHook(NodeDispatcher.NodeDispatcherCallback cb);

    boolean shallWePublishOurPeersLocation();

    boolean shallWeRouteAccordingToOurPeersLocation(int htl);

    void setMasterPassword(String password, boolean inFirstTimeWizard) throws AlreadySetPasswordException, MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException;

    void changeMasterPassword(String oldPassword, String newPassword, boolean inFirstTimeWizard) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException, AlreadySetPasswordException;

    File getMasterPasswordFile();

    void panic();

    void finishPanic();

    boolean awaitingPassword();

    boolean wantEncryptedDatabase();

    boolean wantNoPersistentDatabase();

    boolean hasDatabase();

    String getDatabasePath() throws IOException;

    boolean shouldStoreDeep(Key key, PeerNode source, PeerNode[] routedTo);

    boolean getWriteLocalToDatastore();

    boolean getUseSlashdotCache();

    void createVisibilityAlert();

    int getMinimumMTU();

    void updateMTU();

    MersenneTwister createRandom();

    boolean enableNewLoadManagement(boolean realTimeFlag);

    boolean enableRoutedPing();

    boolean updateIsUrgent();

    byte[] getPluginStoreKey(String storeIdentifier);

    PluginManager getPluginManager();

    PeerManager getPeerManager();

    NodeCrypto getDarknetCrypto();

    public static class AlreadySetPasswordException extends Exception {

       final private static long serialVersionUID = -7328456475029374032L;

    }

    TimeSkewDetectorCallback getTimeSkewDetectorCallback();

    NodeIPDetector getIPDetector();

    RandomSource getRNG();

    Random getWeakRNG();

    Executor getExecutor();

    NodeClientCore getClientCore();

    RequestClient getNonPersistentClientBulk();

    RequestClient getNonPersistentClientRT();

    NodeStats getNodeStats();

    int getLastVersion();

    RequestTracker getRequestTracker();

    IOStatisticCollector getStatisticCollector();

    long getLastBootID();

    long getBootID();

    boolean throttleLocalData();

    TokenBucket getOutputThrottle();

    long getStartupTime();

    InetAddress getLocalHostAddress();

    FreenetInetAddress getLocalHostFreenetAddress();

    PacketSender getPacketSender();

    FetchContext getARKFetcherContext();

    UptimeEstimator getUptimeEstimator();

    PersistentConfig getConfig();

    SecurityLevels getSecurityLevels();

}
