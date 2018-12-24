package freenet.node;

import freenet.client.FetchContext;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.PersistentConfig;
import freenet.config.SubConfig;
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
import freenet.store.*;
import freenet.support.*;
import freenet.support.math.MersenneTwister;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class StubNode implements ProtectedNode {

    @Override
    public MessageCore getUSM() {
        return null;
    }

    @Override
    public DNSRequester getDNSRequester() {
        return null;
    }

    @Override
    public boolean ARKsEnabled() {
        return false;
    }

    @Override
    public boolean enablePacketCoalescing() {
        return false;
    }

    @Override
    public FailureTable getFailureTable() {
        return null;
    }

    @Override
    public NodeDispatcher getDispatcher() {
        return null;
    }

    @Override
    public boolean disableProbabilisticHTLs() {
        return false;
    }

    @Override
    public boolean canWriteDatastoreInsert(short htl) {
        return false;
    }

    @Override
    public NodeGetPubkey getGetPubkey() {
        return null;
    }

    @Override
    public ProtectedNodeCrypto getInternalDarknetCrypto() {
        return null;
    }

    @Override
    public void setDatabaseAwaitingPassword() {

    }

    @Override
    public DatabaseKey getDatabaseKey() {
        return null;
    }

    @Override
    public boolean hasPanicked() {
        return false;
    }

    @Override
    public PubkeyStore getOldPK() {
        return null;
    }

    @Override
    public PubkeyStore getOldPKCache() {
        return null;
    }

    @Override
    public PubkeyStore getOldPKClientCache() {
        return null;
    }

    @Override
    public boolean enableULPRDataPropagation() {
        return false;
    }

    @Override
    public boolean enablePerNodeFailureTables() {
        return false;
    }

    @Override
    public boolean enableSwapping() {
        return false;
    }

    @Override
    public boolean enableSwapQueueing() {
        return false;
    }

    @Override
    public CHKStore getChkDatacache() {
        return null;
    }

    @Override
    public CHKStore getChkDatastore() {
        return null;
    }

    @Override
    public SSKStore getSskDatacache() {
        return null;
    }

    @Override
    public SSKStore getSskDatastore() {
        return null;
    }

    @Override
    public CHKStore getChkSlashdotCache() {
        return null;
    }

    @Override
    public CHKStore getChkClientCache() {
        return null;
    }

    @Override
    public SSKStore getSskSlashdotCache() {
        return null;
    }

    @Override
    public SSKStore getSskClientCache() {
        return null;
    }

    @Override
    public ProgramDirectory setupProgramDir(SubConfig installConfig, String cfgKey, String defaultValue, String shortdesc, String longdesc, SubConfig oldConfig) throws NodeInitException {
        return null;
    }

    @Override
    public <T extends StorableBlock> void closeOldStore(StoreCallback<T> old) {

    }

    @Override
    public TrafficClass getTrafficClass() {
        return null;
    }

    @Override
    public void startProbe(byte htl, long uid, Type type, Listener listener) {

    }

    @Override
    public void makeStore(String val) throws InvalidConfigValueException {

    }

    @Override
    public void writeNodeFile() {

    }

    @Override
    public void writeOpennetFile() {

    }

    @Override
    public boolean isUsingWrapper() {
        return false;
    }

    @Override
    public NodeStarter getNodeStarter() {
        return null;
    }

    @Override
    public ProgramDirectory setupProgramDir(SubConfig installConfig, String cfgKey, String defaultValue, String shortdesc, String longdesc, String moveErrMsg, SubConfig oldConfig) throws NodeInitException {
        return null;
    }

    @Override
    public void lateSetupDatabase(DatabaseKey databaseKey) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {

    }

    @Override
    public void killMasterKeysFile() throws IOException {

    }

    @Override
    public void start(boolean noSwaps) throws NodeInitException {

    }

    @Override
    public SimpleFieldSet exportVolatileFieldSet() {
        return null;
    }

    @Override
    public int routedPing(double loc2, byte[] pubKeyHash) {
        return 0;
    }

    @Override
    public Object makeRequestSender(Key key, short htl, long uid, RequestTag tag, PeerNode source, boolean localOnly, boolean ignoreStore, boolean offersOnly, boolean canReadClientCache, boolean canWriteClientCache, boolean realTimeFlag) {
        return null;
    }

    @Override
    public KeyBlock fetch(Key key, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, BlockMetadata meta) {
        return null;
    }

    @Override
    public SSKBlock fetch(NodeSSK key, boolean dontPromote, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, BlockMetadata meta) {
        return null;
    }

    @Override
    public CHKBlock fetch(NodeCHK key, boolean dontPromote, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, BlockMetadata meta) {
        return null;
    }

    @Override
    public Map<DataStoreInstanceType, DataStoreStats> getDataStoreStats() {
        return null;
    }

    @Override
    public long getMaxTotalKeys() {
        return 0;
    }

    @Override
    public void dumpStoreHits() {

    }

    @Override
    public void storeShallow(CHKBlock block, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR) {

    }

    @Override
    public void store(KeyBlock block, boolean deep, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR) throws KeyCollisionException {

    }

    @Override
    public void storeInsert(SSKBlock block, boolean deep, boolean overwrite, boolean canWriteClientCache, boolean canWriteDatastore) throws KeyCollisionException {

    }

    @Override
    public void storeShallow(SSKBlock block, boolean canWriteClientCache, boolean canWriteDatastore, boolean fromULPR) throws KeyCollisionException {

    }

    @Override
    public void store(SSKBlock block, boolean deep, boolean overwrite, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR) throws KeyCollisionException {

    }

    @Override
    public short decrementHTL(PeerNode source, short htl) {
        return 0;
    }

    @Override
    public CHKInsertSender makeInsertSender(NodeCHK key, short htl, long uid, InsertTag tag, PeerNode source, byte[] headers, PartiallyReceivedBlock prb, boolean fromStore, boolean canWriteClientCache, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff, boolean realTimeFlag) {
        return null;
    }

    @Override
    public SSKInsertSender makeInsertSender(SSKBlock block, short htl, long uid, InsertTag tag, PeerNode source, boolean fromStore, boolean canWriteClientCache, boolean canWriteDatastore, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff, boolean realTimeFlag) {
        return null;
    }

    @Override
    public String getStatus() {
        return null;
    }

    @Override
    public String getTMCIPeerList() {
        return null;
    }

    @Override
    public ClientKeyBlock fetchKey(ClientKey key, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore) throws KeyVerifyException {
        return null;
    }

    @Override
    public ClientKeyBlock fetch(ClientSSK clientSSK, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore) throws SSKVerifyException {
        return null;
    }

    @Override
    public void exit(int reason) {

    }

    @Override
    public void exit(String reason) {

    }

    @Override
    public boolean isStopping() {
        return false;
    }

    @Override
    public void park() {

    }

    @Override
    public NodeUpdateManager getNodeUpdater() {
        return null;
    }

    @Override
    public DarknetPeerNode[] getDarknetConnections() {
        return new DarknetPeerNode[0];
    }

    @Override
    public boolean addPeerConnection(PeerNode pn) {
        return false;
    }

    @Override
    public void removePeerConnection(PeerNode pn) {

    }

    @Override
    public void onConnectedPeer() {

    }

    @Override
    public int getFNPPort() {
        return 0;
    }

    @Override
    public boolean isOudated() {
        return false;
    }

    @Override
    public void registerNodeToNodeMessageListener(int type, NodeToNodeMessageListener listener) {

    }

    @Override
    public void receivedNodeToNodeMessage(Message m, PeerNode src) {

    }

    @Override
    public void receivedNodeToNodeMessage(PeerNode src, int type, ShortBuffer messageData, boolean partingMessage) {

    }

    @Override
    public void handleNodeToNodeTextMessageSimpleFieldSet(SimpleFieldSet fs, DarknetPeerNode source, int fileNumber) throws FSParseException {

    }

    @Override
    public String getMyName() {
        return null;
    }

    @Override
    public LocationManager getLocationManager() {
        return null;
    }

    @Override
    public PeerNode[] getPeerNodes() {
        return new PeerNode[0];
    }

    @Override
    public PeerNode[] getConnectedPeers() {
        return new PeerNode[0];
    }

    @Override
    public PeerNode getPeerNode(String nodeIdentifier) {
        return null;
    }

    @Override
    public boolean isHasStarted() {
        return false;
    }

    @Override
    public void queueRandomReinsert(KeyBlock block) {

    }

    @Override
    public String getExtraPeerDataDir() {
        return null;
    }

    @Override
    public boolean noConnectedPeers() {
        return false;
    }

    @Override
    public double getLocation() {
        return 0;
    }

    @Override
    public double getLocationChangeSession() {
        return 0;
    }

    @Override
    public int getAverageOutgoingSwapTime() {
        return 0;
    }

    @Override
    public long getSendSwapInterval() {
        return 0;
    }

    @Override
    public boolean isAdvancedModeEnabled() {
        return false;
    }

    @Override
    public boolean isFProxyJavascriptEnabled() {
        return false;
    }

    @Override
    public int getNumARKFetchers() {
        return 0;
    }

    @Override
    public void sentPayload(int len) {

    }

    @Override
    public long getTotalPayloadSent() {
        return 0;
    }

    @Override
    public void setName(String key) throws InvalidConfigValueException, NodeNeedRestartException {

    }

    @Override
    public Ticker getTicker() {
        return null;
    }

    @Override
    public int getUnclaimedFIFOSize() {
        return 0;
    }

    @Override
    public void connectToSeednode(SeedServerTestPeerNode node) throws OpennetDisabledException, FSParseException, PeerParseException, ReferenceSignatureVerificationException {

    }

    @Override
    public void connect(Node node, DarknetPeerNode.FRIEND_TRUST trust, DarknetPeerNode.FRIEND_VISIBILITY visibility) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException {

    }

    @Override
    public short maxHTL() {
        return 0;
    }

    @Override
    public int getDarknetPortNumber() {
        return 0;
    }

    @Override
    public int getOutputBandwidthLimit() {
        return 0;
    }

    @Override
    public int getInputBandwidthLimit() {
        return 0;
    }

    @Override
    public long getStoreSize() {
        return 0;
    }

    @Override
    public void setTimeSkewDetectedUserAlert() {

    }

    @Override
    public File getNodeDir() {
        return null;
    }

    @Override
    public File getCfgDir() {
        return null;
    }

    @Override
    public File getUserDir() {
        return null;
    }

    @Override
    public File getRunDir() {
        return null;
    }

    @Override
    public File getStoreDir() {
        return null;
    }

    @Override
    public File getPluginDir() {
        return null;
    }

    @Override
    public ProgramDirectory nodeDir() {
        return null;
    }

    @Override
    public ProgramDirectory cfgDir() {
        return null;
    }

    @Override
    public ProgramDirectory userDir() {
        return null;
    }

    @Override
    public ProgramDirectory runDir() {
        return null;
    }

    @Override
    public ProgramDirectory storeDir() {
        return null;
    }

    @Override
    public ProgramDirectory pluginDir() {
        return null;
    }

    @Override
    public DarknetPeerNode createNewDarknetNode(SimpleFieldSet fs, DarknetPeerNode.FRIEND_TRUST trust, DarknetPeerNode.FRIEND_VISIBILITY visibility) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException {
        return null;
    }

    @Override
    public OpennetPeerNode createNewOpennetNode(SimpleFieldSet fs) throws FSParseException, OpennetDisabledException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException {
        return null;
    }

    @Override
    public SeedServerTestPeerNode createNewSeedServerTestPeerNode(SimpleFieldSet fs) throws FSParseException, OpennetDisabledException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException {
        return null;
    }

    @Override
    public OpennetPeerNode addNewOpennetNode(SimpleFieldSet fs, OpennetManager.ConnectionType connectionType) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
        return null;
    }

    @Override
    public byte[] getOpennetPubKeyHash() {
        return new byte[0];
    }

    @Override
    public byte[] getDarknetPubKeyHash() {
        return new byte[0];
    }

    @Override
    public boolean isOpennetEnabled() {
        return false;
    }

    @Override
    public SimpleFieldSet exportDarknetPublicFieldSet() {
        return null;
    }

    @Override
    public SimpleFieldSet exportOpennetPublicFieldSet() {
        return null;
    }

    @Override
    public SimpleFieldSet exportDarknetPrivateFieldSet() {
        return null;
    }

    @Override
    public SimpleFieldSet exportOpennetPrivateFieldSet() {
        return null;
    }

    @Override
    public boolean dontDetect() {
        return false;
    }

    @Override
    public int getOpennetFNPPort() {
        return 0;
    }

    @Override
    public OpennetManager getOpennet() {
        return null;
    }

    @Override
    public boolean passOpennetRefsThroughDarknet() {
        return false;
    }

    @Override
    public Set<ForwardPort> getPublicInterfacePorts() {
        return null;
    }

    @Override
    public long getUptime() {
        return 0;
    }

    @Override
    public UdpSocketHandler[] getPacketSocketHandlers() {
        return new UdpSocketHandler[0];
    }

    @Override
    public int getMaxOpennetPeers() {
        return 0;
    }

    @Override
    public void onAddedValidIP() {

    }

    @Override
    public boolean isSeednode() {
        return false;
    }

    @Override
    public boolean wantAnonAuth(boolean isOpennet) {
        return false;
    }

    @Override
    public boolean wantAnonAuthChangeIP(boolean isOpennet) {
        return false;
    }

    @Override
    public boolean opennetDefinitelyPortForwarded() {
        return false;
    }

    @Override
    public boolean darknetDefinitelyPortForwarded() {
        return false;
    }

    @Override
    public boolean hasKey(Key key, boolean canReadClientCache, boolean forULPR) {
        return false;
    }

    @Override
    public void setLocation(double loc) {

    }

    @Override
    public boolean peersWantKey(Key key) {
        return false;
    }

    @Override
    public void setDispatcherHook(NodeDispatcher.NodeDispatcherCallback cb) {

    }

    @Override
    public boolean shallWePublishOurPeersLocation() {
        return false;
    }

    @Override
    public boolean shallWeRouteAccordingToOurPeersLocation(int htl) {
        return false;
    }

    @Override
    public void setMasterPassword(String password, boolean inFirstTimeWizard) throws AlreadySetPasswordException, MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {

    }

    @Override
    public void changeMasterPassword(String oldPassword, String newPassword, boolean inFirstTimeWizard) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException, AlreadySetPasswordException {

    }

    @Override
    public File getMasterPasswordFile() {
        return null;
    }

    @Override
    public void panic() {

    }

    @Override
    public void finishPanic() {

    }

    @Override
    public boolean awaitingPassword() {
        return false;
    }

    @Override
    public boolean wantEncryptedDatabase() {
        return false;
    }

    @Override
    public boolean wantNoPersistentDatabase() {
        return false;
    }

    @Override
    public boolean hasDatabase() {
        return false;
    }

    @Override
    public String getDatabasePath() throws IOException {
        return null;
    }

    @Override
    public boolean shouldStoreDeep(Key key, PeerNode source, PeerNode[] routedTo) {
        return false;
    }

    @Override
    public boolean getWriteLocalToDatastore() {
        return false;
    }

    @Override
    public boolean getUseSlashdotCache() {
        return false;
    }

    @Override
    public void createVisibilityAlert() {

    }

    @Override
    public int getMinimumMTU() {
        return 0;
    }

    @Override
    public void updateMTU() {

    }

    @Override
    public MersenneTwister createRandom() {
        return null;
    }

    @Override
    public boolean enableNewLoadManagement(boolean realTimeFlag) {
        return false;
    }

    @Override
    public boolean enableRoutedPing() {
        return false;
    }

    @Override
    public boolean updateIsUrgent() {
        return false;
    }

    @Override
    public byte[] getPluginStoreKey(String storeIdentifier) {
        return new byte[0];
    }

    @Override
    public PluginManager getPluginManager() {
        return null;
    }

    @Override
    public PeerManager getPeerManager() {
        return null;
    }

    @Override
    public NodeCrypto getDarknetCrypto() {
        return null;
    }

    @Override
    public TimeSkewDetectorCallback getTimeSkewDetectorCallback() {
        return null;
    }

    @Override
    public NodeIPDetector getIPDetector() {
        return null;
    }

    @Override
    public RandomSource getRNG() {
        return null;
    }

    @Override
    public Random getWeakRNG() {
        return null;
    }

    @Override
    public Executor getExecutor() {
        return null;
    }

    @Override
    public NodeClientCore getClientCore() {
        return null;
    }

    @Override
    public RequestClient getNonPersistentClientBulk() {
        return null;
    }

    @Override
    public RequestClient getNonPersistentClientRT() {
        return null;
    }

    @Override
    public NodeStats getNodeStats() {
        return null;
    }

    @Override
    public int getLastVersion() {
        return 0;
    }

    @Override
    public RequestTracker getRequestTracker() {
        return null;
    }

    @Override
    public IOStatisticCollector getStatisticCollector() {
        return null;
    }

    @Override
    public long getLastBootID() {
        return 0;
    }

    @Override
    public long getBootID() {
        return 0;
    }

    @Override
    public boolean throttleLocalData() {
        return false;
    }

    @Override
    public TokenBucket getOutputThrottle() {
        return null;
    }

    @Override
    public long getStartupTime() {
        return 0;
    }

    @Override
    public InetAddress getLocalHostAddress() {
        return null;
    }

    @Override
    public FreenetInetAddress getLocalHostFreenetAddress() {
        return null;
    }

    @Override
    public PacketSender getPacketSender() {
        return null;
    }

    @Override
    public FetchContext getARKFetcherContext() {
        return null;
    }

    @Override
    public UptimeEstimator getUptimeEstimator() {
        return null;
    }

    @Override
    public PersistentConfig getConfig() {
        return null;
    }

    @Override
    public SecurityLevels getSecurityLevels() {
        return null;
    }
}
