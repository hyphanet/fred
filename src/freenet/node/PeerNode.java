package freenet.node;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.Vector;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import freenet.support.math.MersenneTwister;

import net.i2p.util.NativeBigInteger;
import freenet.client.FetchResult;
import freenet.client.async.USKRetriever;
import freenet.client.async.USKRetrieverCallback;
import freenet.crypt.BlockCipher;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.Global;
import freenet.crypt.HMAC;
import freenet.crypt.KeyAgreementSchemeContext;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.AddressTracker;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PacketSocketHandler;
import freenet.io.comm.Peer;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.PeerRestartedException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.SocketHandler;
import freenet.io.xfer.PacketThrottle;
import freenet.io.xfer.ThrottleDeprecatedException;
import freenet.io.xfer.WaitedTooLongException;
import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.keys.USK;
import freenet.node.NodeStats.PeerLoadStats;
import freenet.node.NodeStats.RequestType;
import freenet.node.NodeStats.RunningRequestsSnapshot;
import freenet.node.OpennetManager.ConnectionType;
import freenet.node.PeerManager.PeerStatusChangeListener;
import freenet.node.PeerNode.IncomingLoadSummaryStats;
import freenet.node.PeerNode.RequestLikelyAcceptedState;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.WeakHashSet;
import freenet.support.WouldBlockException;
import freenet.support.Logger.LogLevel;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;
import freenet.support.math.TimeDecayingRunningAverage;
import freenet.support.transport.ip.HostnameSyntaxException;
import freenet.support.transport.ip.IPUtil;

/**
 * @author amphibian
 *
 * Represents a peer we are connected to. One of the major issues
 * is that we can rekey, or a node can go down and come back up
 * while we are connected to it, and we want to reinitialize the
 * packet numbers when this happens. Hence we separate a lot of
 * code into SessionKey, which handles all communications to and
 * from this peer over the duration of a single key.
 */
public abstract class PeerNode implements USKRetrieverCallback, BasePeerNode {

	private String lastGoodVersion;
	/**
	 * True if this peer has a build number older than our last-known-good build number.
	 * Note that even if this is true, the node can still be 'connected'.
	 */
	protected boolean unroutableOlderVersion;
	/**
	 * True if this peer reports that our build number is before their last-known-good build number.
	 * Note that even if this is true, the node can still be 'connected'.
	 */
	protected boolean unroutableNewerVersion;
	protected boolean disableRouting;
	protected boolean disableRoutingHasBeenSetLocally;
	protected boolean disableRoutingHasBeenSetRemotely;
	/*
	* Buffer of Ni,Nr,g^i,g^r,ID
	*/
	private byte[] jfkBuffer;
	//TODO: sync ?

	protected byte[] jfkKa;
	protected byte[] incommingKey;
	protected byte[] jfkKe;
	protected byte[] outgoingKey;
	protected byte[] jfkMyRef;
	protected byte[] hmacKey;
	protected byte[] ivKey;
	protected byte[] ivNonce;
	protected int ourInitialSeqNum;
	protected int theirInitialSeqNum;
	protected int ourInitialMsgID;
	protected int theirInitialMsgID;
	// The following is used only if we are the initiator

	protected long jfkContextLifetime = 0;
	/** My low-level address for SocketManager purposes */
	private Peer detectedPeer;
	/** My OutgoingPacketMangler i.e. the object which encrypts packets sent to this node */
	private final OutgoingPacketMangler outgoingMangler;
	/** Advertised addresses */
	protected Vector<Peer> nominalPeer;
	/** The PeerNode's report of our IP address */
	private Peer remoteDetectedPeer;
	/** Is this a testnet node? */
	public final boolean testnetEnabled;
	/** Packets sent/received on the current preferred key */
	private SessionKey currentTracker;
	/** Previous key - has a separate packet number space */
	private SessionKey previousTracker;
	/** When did we last rekey (promote the unverified tracker to new) ? */
	private long timeLastRekeyed;
	/** How much data did we send with the current tracker ? */
	private long totalBytesExchangedWithCurrentTracker = 0;
	/** Are we rekeying ? */
	private boolean isRekeying = false;
	/** Unverified tracker - will be promoted to currentTracker if
	* we receive packets on it
	*/
	private SessionKey unverifiedTracker;
	/** When did we last send a packet? */
	private long timeLastSentPacket;
	/** When did we last receive a packet? */
	private long timeLastReceivedPacket;
	/** When did we last receive a non-auth packet? */
	private long timeLastReceivedDataPacket;
	/** When did we last receive an ack? */
	private long timeLastReceivedAck;
	/** When was isConnected() last true? */
	private long timeLastConnected;
	/** When was isRoutingCompatible() last true? */
	private long timeLastRoutable;
	/** Time added or restarted (reset on startup unlike peerAddedTime) */
	private long timeAddedOrRestarted;
	
	private long countSelectionsSinceConnected = 0;
	// 5mins; yes it's alchemy!
	public static final int SELECTION_SAMPLING_PERIOD = 5 * 60 * 1000;
	// 30%; yes it's alchemy too! and probably *way* too high to serve any purpose
	public static final int SELECTION_PERCENTAGE_WARNING = 30;
	// Minimum number of routable peers to have for the selection code to have any effect
	public static final int SELECTION_MIN_PEERS = 5;
	// Should be good enough provided we don't get selected more than 10 times per/sec
	// Lower the following value if you want to spare memory... or better switch from a TreeSet to a bit field.
	public static final int SELECTION_MAX_SAMPLES = 10 * SELECTION_SAMPLING_PERIOD / 1000;

	/** Are we connected? If not, we need to start trying to
	* handshake.
	*/
	private boolean isConnected;
	private boolean isRoutable;

	/** Used by maybeOnConnect */
	private boolean wasDisconnected = true;
	
	/** Were we removed from the routing table? 
	 * Used as a cache to avoid accessing PeerManager if not needed. */
	private boolean removed;
	
	/**
	* ARK fetcher.
	*/
	private USKRetriever arkFetcher;
	/** My ARK SSK public key; edition is the next one, not the current one,
	* so this is what we want to fetch. */
	private USK myARK;
	/** Number of handshake attempts since last successful connection or ARK fetch */
	private int handshakeCount;
	/** After this many failed handshakes, we start the ARK fetcher. */
	private static final int MAX_HANDSHAKE_COUNT = 2;
	/** Current location in the keyspace, or -1 if it is unknown */
	private double currentLocation;
	/** Current locations of our peer's peers */
	private double[] currentPeersLocation;
	/** Time the location was set */
	private long locSetTime;
	/** Node identity; for now a block of data, in future a
	* public key (FIXME). Cannot be changed.
	*/
	final byte[] identity;
	final String identityAsBase64String;
	/** Hash of node identity. Used in setup key. */
	final byte[] identityHash;
	/** Hash of hash of node identity. Used in setup key. */
	final byte[] identityHashHash;
	/** Semi-unique ID used to help in mapping the network (see the code that uses it). Note this is for diagnostic
	* purposes only and should be removed along with the code that uses it eventually - FIXME */
	final long swapIdentifier;
	/** Negotiation types supported */
	int[] negTypes;
	/** Integer hash of node identity. Used as hashCode(). */
	final int hashCode;
	/** The Node we serve */
	final Node node;
	/** The PeerManager we serve */
	final PeerManager peers;
	/** MessageItem's to send ASAP.
	 * LOCKING: Lock on self, always take that lock last. Sometimes used inside PeerNode.this lock. */
	private final PeerMessageQueue messageQueue;
	/** When did we last receive a SwapRequest? */
	private long timeLastReceivedSwapRequest;
	/** Average interval between SwapRequest's */
	private final RunningAverage swapRequestsInterval;
	/** When did we last receive a probe request? */
	private long timeLastReceivedProbeRequest;
	/** Average interval between probe requests */
	private final RunningAverage probeRequestsInterval;
	/** Should we decrement HTL when it is at the maximum?
	* This decision is made once per node to prevent giving
	* away information that can make correlation attacks much
	* easier.
	*/
	final boolean decrementHTLAtMaximum;
	/** Should we decrement HTL when it is at the minimum (1)? */
	final boolean decrementHTLAtMinimum;

	/** Time at which we should send the next handshake request */
	protected long sendHandshakeTime;
	/** Time after which we log message requeues while rate limiting */
	private long nextMessageRequeueLogTime;
	/** Interval between rate limited message requeue logs (in milliseconds) */
	private long messageRequeueLogRateLimitInterval = 1000;
	/** Number of messages to be requeued after which we rate limit logging of such */
	private int messageRequeueLogRateLimitThreshold = 15;
	/** Version of the node */
	private String version;
	/** Total input */
	private long totalInputSinceStartup;
	/** Total output */
	private long totalOutputSinceStartup;
	/** Peer node crypto group; changing this means new noderef */
	final DSAGroup peerCryptoGroup;

	/** Peer node public key; changing this means new noderef */
	final DSAPublicKey peerPubKey;
	private boolean isSignatureVerificationSuccessfull;
	/** Incoming setup key. Used to decrypt incoming auth packets.
	* Specifically: K_node XOR H(setupKey).
	*/
	final byte[] incomingSetupKey;
	/** Outgoing setup key. Used to encrypt outgoing auth packets.
	* Specifically: setupKey XOR H(K_node).
	*/
	final byte[] outgoingSetupKey;
	/** Incoming setup cipher (see above) */
	final BlockCipher incomingSetupCipher;
	/** Outgoing setup cipher (see above) */
	final BlockCipher outgoingSetupCipher;
	/** Anonymous-connect cipher. This is used in link setup if
	 * we are trying to get a connection to this node even though
	 * it doesn't know us, e.g. as a seednode. */
	final BlockCipher anonymousInitiatorSetupCipher;
	/** The context object for the currently running negotiation. */
	private KeyAgreementSchemeContext ctx;
	/** The other side's boot ID. This is a random number generated
	* at startup.
	*/
	private long bootID;
	/** Our boot ID. This is set to a random number on startup, and then reset whenever
	 * we dump the in-flight messages and call disconnected() on their clients, i.e.
	 * whenever we call disconnected(true, ...) */
	private long myBootID;
	/** myBootID at the time of the last successful completed handshake. */
	private long myLastSuccessfulBootID;

	/** If true, this means last time we tried, we got a bogus noderef */
	private boolean bogusNoderef;
	/** The time at which we last completed a connection setup. */
	private long connectedTime;
	/** The status of this peer node in terms of Node.PEER_NODE_STATUS_* */
	public int peerNodeStatus = PeerManager.PEER_NODE_STATUS_DISCONNECTED;

	static final int CHECK_FOR_SWAPPED_TRACKERS_INTERVAL = FNPPacketMangler.SESSION_KEY_REKEYING_INTERVAL / 30;

	static final byte[] TEST_AS_BYTES;
	static {
		try {
			TEST_AS_BYTES = "test".getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}
	}

	/** Holds a String-Long pair that shows which message types (as name) have been send to this peer. */
	private final Hashtable<String, Long> localNodeSentMessageTypes = new Hashtable<String, Long>();
	/** Holds a String-Long pair that shows which message types (as name) have been received by this peer. */
	private final Hashtable<String, Long> localNodeReceivedMessageTypes = new Hashtable<String, Long>();

	/** Hold collected IP addresses for handshake attempts, populated by DNSRequestor */
	private Peer[] handshakeIPs;
	/** The last time we attempted to update handshakeIPs */
	private long lastAttemptedHandshakeIPUpdateTime;
	/** True if we have never connected to this peer since it was added to this node */
	protected boolean neverConnected;
	/** When this peer was added to this node.
	 * This is used differently by opennet and darknet nodes.
	 * Darknet nodes clear it after connecting but persist it across restarts, and clear it on restart unless the peer has never connected, or if it is more than 30 days ago.
	 * Opennet nodes clear it after the post-connect grace period elapses, and don't persist it across restarts.
	 */
	protected long peerAddedTime = 1;
	/** Average proportion of requests which are rejected or timed out */
	private TimeDecayingRunningAverage pRejected;
	/** Total low-level input bytes */
	private long totalBytesIn;
	/** Total low-level output bytes */
	private long totalBytesOut;
	/** Times had routable connection when checked */
	private long hadRoutableConnectionCount;
	/** Times checked for routable connection */
	private long routableConnectionCheckCount;
	/** Delta between our clock and his clock (positive = his clock is fast, negative = our clock is fast) */
	private long clockDelta;
	/** Percentage uptime of this node, 0 if they haven't said */
	private byte uptime;

	/** If the clock delta is more than this constant, we don't talk to the node. Reason: It may not be up to date,
	* it will have difficulty resolving date-based content etc. */
	private static final long MAX_CLOCK_DELTA = 24L * 60L * 60L * 1000L;
	/** 1 hour after the node is disconnected, if it is still disconnected and hasn't connected in that time,
	 * clear the message queue */
	private static final long CLEAR_MESSAGE_QUEUE_AFTER = 60 * 60 * 1000L;
	/** A WeakReference to this object. Can be taken whenever a node object needs to refer to this object for a
	 * long time, but without preventing it from being GC'ed. */
	final WeakReference<PeerNode> myRef;
	/** The node is being disconnected, but it may take a while. */
	private boolean disconnecting;
	/** When did we last disconnect? Not Disconnected because a discrete event */
	long timeLastDisconnect;
	/** Previous time of disconnection */
	long timePrevDisconnect;

	// Burst-only mode
	/** True if we are currently sending this peer a burst of handshake requests */
	private boolean isBursting;
	/** Number of handshake attempts (while in ListenOnly mode) since the beginning of this burst */
	private int listeningHandshakeBurstCount;
	/** Total number of handshake attempts (while in ListenOnly mode) to be in this burst */
	private int listeningHandshakeBurstSize;

	/** The set of the listeners that needs to be notified when status changes. It uses WeakReference, so there is no need to deregister*/
	private Set<PeerManager.PeerStatusChangeListener> listeners=Collections.synchronizedSet(new WeakHashSet<PeerStatusChangeListener>());

	// NodeCrypto for the relevant node reference for this peer's type (Darknet or Opennet at this time))
	protected NodeCrypto crypto;

	/**
	 * Some alchemy we use in PeerNode.shouldBeExcludedFromPeerList()
	 */
	public static final int BLACK_MAGIC_BACKOFF_PRUNING_TIME = 5 * 60 * 1000;
	public static final double BLACK_MAGIC_BACKOFF_PRUNING_PERCENTAGE = 0.9;

	/**
	 * For FNP link setup:
	 *  The initiator has to ensure that nonces send back by the
	 *  responder in message2 match what was chosen in message 1
	 */
	protected final LinkedList<byte[]> jfkNoncesSent = new LinkedList<byte[]>();
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	private PacketFormat packetFormat;
	MersenneTwister paddingGen;

	/**
	 * If this returns true, we will generate the identity from the pubkey.
	 * Only set this if you don't want to send an identity, e.g. for anonymous
	 * initiator crypto where we need a small noderef and we don't use the
	 * identity anyway because we don't auto-reconnect.
	 */
	protected abstract boolean generateIdentityFromPubkey();

	protected boolean ignoreLastGoodVersion() {
		return false;
	}

	/**
	* Create a PeerNode from a SimpleFieldSet containing a
	* node reference for one. This must contain the following
	* fields:
	* - identity
	* - version
	* - location
	* - physical.udp
	* - setupKey
	* Do not add self to PeerManager.
	* @param fs The SimpleFieldSet to parse
	* @param node2 The running Node we are part of.
	*/
	public PeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, PeerManager peers, boolean fromLocal, boolean fromAnonymousInitiator, OutgoingPacketMangler mangler, boolean isOpennet) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		boolean noSig = false;
		if(fromLocal || fromAnonymousInitiator) noSig = true;
		myRef = new WeakReference<PeerNode>(this);
		this.checkStatusAfterBackoff = new PeerNodeBackoffStatusChecker(myRef);
		this.outgoingMangler = mangler;
		this.node = node2;
		this.crypto = crypto;
		this.peers = peers;
		this.backedOffPercent = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.backedOffPercentRT = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.backedOffPercentBulk = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.myBootID = node2.bootID;
		version = fs.get("version");
		Version.seenVersion(version);
		try {
			simpleVersion = Version.getArbitraryBuildNumber(version);
		} catch (VersionParseException e2) {
			throw new FSParseException("Invalid version "+version+" : "+e2);
		}
		String locationString = fs.get("location");
		String[] peerLocationsString = fs.getAll("peersLocation");

		currentLocation = Location.getLocation(locationString);
		if(peerLocationsString != null) {
			double[] peerLocations = new double[peerLocationsString.length];
			for(int i = 0; i < peerLocationsString.length; i++)
				peerLocations[i] = Location.getLocation(peerLocationsString[i]);
			currentPeersLocation = peerLocations;
		}
		locSetTime = System.currentTimeMillis();

		disableRouting = disableRoutingHasBeenSetLocally = false;
		disableRoutingHasBeenSetRemotely = false; // Assume so

		lastGoodVersion = fs.get("lastGoodVersion");
		updateVersionRoutablity();

		testnetEnabled = fs.getBoolean("testnet", false);
		if(testnetEnabled) {
			String err = "Ignoring incompatible testnet node " + detectedPeer;
			Logger.error(this, err);
			throw new PeerParseException(err);
		}

		negTypes = fs.getIntArray("auth.negTypes");
		if(negTypes == null || negTypes.length == 0) {
			if(fromAnonymousInitiator)
				negTypes = mangler.supportedNegTypes(false); // Assume compatible. Anonymous initiator = short-lived, and we already connected so we know we are.
			else
				throw new FSParseException("No negTypes!");
		}

		if(fs.getBoolean("opennet", false) != isOpennet)
			throw new FSParseException("Trying to parse a darknet peer as opennet or an opennet peer as darknet isOpennet="+isOpennet+" boolean = "+fs.getBoolean("opennet", false)+" string = \""+fs.get("opennet")+"\"");

		/* Read the DSA key material for the peer */
		try {
			SimpleFieldSet sfs = fs.subset("dsaGroup");
			if(sfs == null)
				throw new FSParseException("No dsaGroup - very old reference?");
			else
				this.peerCryptoGroup = DSAGroup.create(sfs);

			sfs = fs.subset("dsaPubKey");
			if(sfs == null || peerCryptoGroup == null)
				throw new FSParseException("No dsaPubKey - very old reference?");
			else
				this.peerPubKey = DSAPublicKey.create(sfs, peerCryptoGroup);

			String signature = fs.get("sig");
			fs.removeValue("sig");
			if(!noSig) {
				try {
					boolean failed = false;
					if(signature == null || peerCryptoGroup == null || peerPubKey == null ||
						(failed = !(DSA.verify(peerPubKey, new DSASignature(signature), new BigInteger(1, SHA256.digest(fs.toOrderedString().getBytes("UTF-8"))), false)))) {
						String errCause = "";
						if(signature == null)
							errCause += " (No signature)";
						if(peerCryptoGroup == null)
							errCause += " (No peer crypto group)";
						if(peerPubKey == null)
							errCause += " (No peer public key)";
						if(failed)
							errCause += " (VERIFICATION FAILED)";
						Logger.error(this, "The integrity of the reference has been compromised!" + errCause + " fs was\n" + fs.toOrderedString());
						this.isSignatureVerificationSuccessfull = false;
						fs.putSingle("sig", signature);
						throw new ReferenceSignatureVerificationException("The integrity of the reference has been compromised!" + errCause);
					} else
						this.isSignatureVerificationSuccessfull = true;
				} catch(NumberFormatException e) {
					Logger.error(this, "Invalid reference: " + e, e);
					throw new ReferenceSignatureVerificationException("The node reference you added is invalid: It does not have a valid signature.");
				} catch(UnsupportedEncodingException e) {
					throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
				}
			} else {
				// Local is always good (assumed)
				this.isSignatureVerificationSuccessfull = true;
			}
		} catch(IllegalBase64Exception e) {
			Logger.error(this, "Caught " + e, e);
			throw new FSParseException(e);
		}

		// Identifier

		if(!generateIdentityFromPubkey()) {
			String identityString = fs.get("identity");
			if(identityString == null)
				throw new PeerParseException("No identity!");
			try {
				identity = Base64.decode(identityString);
			} catch(NumberFormatException e) {
				throw new FSParseException(e);
			} catch(IllegalBase64Exception e) {
				throw new FSParseException(e);
			}
		} else {
			identity = peerPubKey.asBytesHash();
		}

		if(identity == null)
			throw new FSParseException("No identity");
		identityAsBase64String = Base64.encode(identity);
		identityHash = SHA256.digest(identity);
		identityHashHash = SHA256.digest(identityHash);
		swapIdentifier = Fields.bytesToLong(identityHashHash);
		hashCode = Fields.hashCode(identityHash);

		// Setup incoming and outgoing setup ciphers
		byte[] nodeKey = crypto.identityHash;
		byte[] nodeKeyHash = crypto.identityHashHash;

		int digestLength = SHA256.getDigestLength();
		incomingSetupKey = new byte[digestLength];
		for(int i = 0; i < incomingSetupKey.length; i++)
			incomingSetupKey[i] = (byte) (nodeKey[i] ^ identityHashHash[i]);
		outgoingSetupKey = new byte[digestLength];
		for(int i = 0; i < outgoingSetupKey.length; i++)
			outgoingSetupKey[i] = (byte) (nodeKeyHash[i] ^ identityHash[i]);
		if(logMINOR)
			Logger.minor(this, "Keys:\nIdentity:  " + HexUtil.bytesToHex(crypto.myIdentity) +
				"\nThisIdent: " + HexUtil.bytesToHex(identity) +
				"\nNode:      " + HexUtil.bytesToHex(nodeKey) +
				"\nNode hash: " + HexUtil.bytesToHex(nodeKeyHash) +
				"\nThis:      " + HexUtil.bytesToHex(identityHash) +
				"\nThis hash: " + HexUtil.bytesToHex(identityHashHash) +
				"\nFor:       " + getPeer());

		try {
			incomingSetupCipher = new Rijndael(256, 256);
			incomingSetupCipher.initialize(incomingSetupKey);
			outgoingSetupCipher = new Rijndael(256, 256);
			outgoingSetupCipher.initialize(outgoingSetupKey);
			anonymousInitiatorSetupCipher = new Rijndael(256, 256);
			anonymousInitiatorSetupCipher.initialize(identityHash);
		} catch(UnsupportedCipherException e1) {
			Logger.error(this, "Caught: " + e1);
			throw new Error(e1);
		}

		nominalPeer = new Vector<Peer>();
		try {
			String physical[] = fs.getAll("physical.udp");
			if(physical == null) {
				// Leave it empty
			} else {
				for(int i = 0; i < physical.length; i++) {
					Peer p;
					try {
						p = new Peer(physical[i], true, true);
					} catch(HostnameSyntaxException e) {
						if(fromLocal)
							Logger.error(this, "Invalid hostname or IP Address syntax error while parsing peer reference in local peers list: " + physical[i]);
						System.err.println("Invalid hostname or IP Address syntax error while parsing peer reference: " + physical[i]);
						continue;
					} catch (PeerParseException e) {
						if(fromLocal)
							Logger.error(this, "Invalid hostname or IP Address syntax error while parsing peer reference in local peers list: " + physical[i]);
						System.err.println("Invalid hostname or IP Address syntax error while parsing peer reference: " + physical[i]);
						continue;
					} catch (UnknownHostException e) {
						if(fromLocal)
							Logger.error(this, "Invalid hostname or IP Address syntax error while parsing peer reference in local peers list: " + physical[i]);
						System.err.println("Invalid hostname or IP Address syntax error while parsing peer reference: " + physical[i]);
						continue;
					}
					if(!nominalPeer.contains(p))
						nominalPeer.addElement(p);
				}
			}
		} catch(Exception e1) {
			throw new FSParseException(e1);
		}
		if(nominalPeer.isEmpty()) {
			Logger.normal(this, "No IP addresses found for identity '" + identityAsBase64String + "', possibly at location '" + Double.toString(currentLocation) + ": " + userToString());
			detectedPeer = null;
		} else {
			detectedPeer = nominalPeer.firstElement();
		}
		updateShortToString();

		// Don't create trackers until we have a key
		currentTracker = null;
		previousTracker = null;

		timeLastSentPacket = -1;
		timeLastReceivedPacket = -1;
		timeLastReceivedSwapRequest = -1;
		timeLastConnected = -1;
		timeLastRoutable = -1;
		timeAddedOrRestarted = System.currentTimeMillis();

		swapRequestsInterval = new SimpleRunningAverage(50, Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS);
		probeRequestsInterval = new SimpleRunningAverage(50, Node.MIN_INTERVAL_BETWEEN_INCOMING_PROBE_REQUESTS);

		// Not connected yet; need to handshake
		isConnected = false;

		messageQueue = new PeerMessageQueue(this);

		decrementHTLAtMaximum = node.random.nextFloat() < Node.DECREMENT_AT_MAX_PROB;
		decrementHTLAtMinimum = node.random.nextFloat() < Node.DECREMENT_AT_MIN_PROB;

		pingNumber = node.random.nextLong();

		// A SimpleRunningAverage would be a bad choice because it would cause oscillations.
		// So go for a filter.
		pingAverage =
			// Short average otherwise we will reject for a *REALLY* long time after any spike.
			new TimeDecayingRunningAverage(1, 30 * 1000, 0, NodePinger.CRAZY_MAX_PING_TIME, node);

		// TDRA for probability of rejection
		pRejected =
			new TimeDecayingRunningAverage(0, 240 * 1000, 0.0, 1.0, node);

		// ARK stuff.

		parseARK(fs, true, false);

		// Now for the metadata.
		// The metadata sub-fieldset contains data about the node which is not part of the node reference.
		// It belongs to this node, not to the node being described.
		// Therefore, if we are parsing a remotely supplied ref, ignore it.

		long now = System.currentTimeMillis();
		if(fromLocal) {

			SimpleFieldSet metadata = fs.subset("metadata");

			if(metadata != null) {

				// Don't be tolerant of nonexistant domains; this should be an IP address.
				Peer p;
				try {
					String detectedUDPString = metadata.get("detected.udp");
					p = null;
					if(detectedUDPString != null)
						p = new Peer(detectedUDPString, false);
				} catch(UnknownHostException e) {
					p = null;
					Logger.error(this, "detected.udp = " + metadata.get("detected.udp") + " - " + e, e);
				} catch(PeerParseException e) {
					p = null;
					Logger.error(this, "detected.udp = " + metadata.get("detected.udp") + " - " + e, e);
				}
				if(p != null)
					detectedPeer = p;
				updateShortToString();
				String tempTimeLastReceivedPacketString = metadata.get("timeLastReceivedPacket");
				if(tempTimeLastReceivedPacketString != null) {
					long tempTimeLastReceivedPacket = Fields.parseLong(tempTimeLastReceivedPacketString, -1);
					timeLastReceivedPacket = tempTimeLastReceivedPacket;
				}
				String tempTimeLastConnectedString = metadata.get("timeLastConnected");
				if(tempTimeLastConnectedString != null) {
					long tempTimeLastConnected = Fields.parseLong(tempTimeLastConnectedString, -1);
					timeLastConnected = tempTimeLastConnected;
				}
				String tempTimeLastRoutableString = metadata.get("timeLastRoutable");
				if(tempTimeLastRoutableString != null) {
					long tempTimeLastRoutable = Fields.parseLong(tempTimeLastRoutableString, -1);
					timeLastRoutable = tempTimeLastRoutable;
				}
				if(timeLastConnected < 1 && timeLastReceivedPacket > 1)
					timeLastConnected = timeLastReceivedPacket;
				if(timeLastRoutable < 1 && timeLastReceivedPacket > 1)
					timeLastRoutable = timeLastReceivedPacket;
				String tempPeerAddedTimeString = metadata.get("peerAddedTime");
				if(tempPeerAddedTimeString != null) {
					long tempPeerAddedTime = Fields.parseLong(tempPeerAddedTimeString, 0);
					peerAddedTime = tempPeerAddedTime;
				} else
					peerAddedTime = 0; // This is normal: Not only do exported refs not include it, opennet peers don't either.
				neverConnected = Fields.stringToBool(metadata.get("neverConnected"), false);
				maybeClearPeerAddedTimeOnRestart(now);
				String tempHadRoutableConnectionCountString = metadata.get("hadRoutableConnectionCount");
				if(tempHadRoutableConnectionCountString != null) {
					long tempHadRoutableConnectionCount = Fields.parseLong(tempHadRoutableConnectionCountString, 0);
					hadRoutableConnectionCount = tempHadRoutableConnectionCount;
				} else
					hadRoutableConnectionCount = 0;
				String tempRoutableConnectionCheckCountString = metadata.get("routableConnectionCheckCount");
				if(tempRoutableConnectionCheckCountString != null) {
					long tempRoutableConnectionCheckCount = Fields.parseLong(tempRoutableConnectionCheckCountString, 0);
					routableConnectionCheckCount = tempRoutableConnectionCheckCount;
				} else
					routableConnectionCheckCount = 0;
			}
		} else {
			neverConnected = true;
			peerAddedTime = now;
		}
		// populate handshakeIPs so handshakes can start ASAP
		lastAttemptedHandshakeIPUpdateTime = 0;
		maybeUpdateHandshakeIPs(true);

		listeningHandshakeBurstCount = 0;
		listeningHandshakeBurstSize = Node.MIN_BURSTING_HANDSHAKE_BURST_SIZE
			+ node.random.nextInt(Node.RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE);

		if(isBurstOnly()) {
			Logger.minor(this, "First BurstOnly mode handshake in "+(sendHandshakeTime - now)+"ms for "+shortToString()+" (count: "+listeningHandshakeBurstCount+", size: "+listeningHandshakeBurstSize+ ')');
		}

		if(fromLocal)
			innerCalcNextHandshake(false, false, now); // Let them connect so we can recognise we are NATed

		else
			sendHandshakeTime = now;  // Be sure we're ready to handshake right away

		totalInputSinceStartup = fs.getLong("totalInput", 0);
		totalOutputSinceStartup = fs.getLong("totalOutput", 0);

		int lastNegType = negTypes[negTypes.length - 1];
		
		byte buffer[] = new byte[16];
		node.random.nextBytes(buffer);
		paddingGen = new MersenneTwister(buffer);

	// status may have changed from PEER_NODE_STATUS_DISCONNECTED to PEER_NODE_STATUS_NEVER_CONNECTED
	}

	protected abstract void maybeClearPeerAddedTimeOnRestart(long now);

	private boolean parseARK(SimpleFieldSet fs, boolean onStartup, boolean forDiffNodeRef) {
		USK ark = null;
		long arkNo = 0;
		try {
			String arkPubKey = fs.get("ark.pubURI");
			arkNo = fs.getLong("ark.number", -1);
			if(arkPubKey == null && arkNo <= -1) {
				// ark.pubURI and ark.number are always optional as a pair
				return false;
			} else if(arkPubKey != null && arkNo > -1) {
				if(onStartup) arkNo++;
				// this is the number of the ref we are parsing.
				// we want the number of the next edition.
				// on startup we want to fetch the old edition in case there's been a corruption.
				FreenetURI uri = new FreenetURI(arkPubKey);
				ClientSSK ssk = new ClientSSK(uri);
				ark = new USK(ssk, arkNo);
			} else if(forDiffNodeRef && arkPubKey == null && myARK != null && arkNo > -1) {
				// get the ARK URI from the previous ARK and the edition from the SFS
				ark = myARK.copy(arkNo);
			} else if(forDiffNodeRef && arkPubKey != null && myARK != null && arkNo <= -1) {
				// the SFS must contain an edition if it contains a arkPubKey
				Logger.error(this, "Got a differential node reference from " + this + " with an arkPubKey but no ARK edition");
				return false;
			} else return false;
		} catch(MalformedURLException e) {
			Logger.error(this, "Couldn't parse ARK info for " + this + ": " + e, e);
		} catch(NumberFormatException e) {
			Logger.error(this, "Couldn't parse ARK info for " + this + ": " + e, e);
		}

		synchronized(this) {
			if(ark != null) {
				if((myARK == null) || ((myARK != ark) && !myARK.equals(ark))) {
					myARK = ark;
					return true;
				}
			}
		}
		return false;
	}

	/**
	* Get my low-level address. This is the address that packets have been received from from this node.
	*
	* Normally this is the address that packets have been received from from this node.
	* However, if ignoreSourcePort is set, we will search for a similar address with a different port
	* number in the node reference.
	*/
	public synchronized Peer getPeer() {
		return detectedPeer;
	}

	/**
	* Returns an array with the advertised addresses and the detected one
	*/
	protected synchronized Peer[] getHandshakeIPs() {
		return handshakeIPs;
	}

	private String handshakeIPsToString() {
		Peer[] localHandshakeIPs;
		synchronized(this) {
			localHandshakeIPs = handshakeIPs;
		}
		if(localHandshakeIPs == null)
			return "null";
		StringBuilder toOutputString = new StringBuilder(1024);
		boolean needSep = false;
		toOutputString.append("[ ");
		for(int i = 0; i < localHandshakeIPs.length; i++) {
			if(needSep)
				toOutputString.append(", ");
			if(localHandshakeIPs[i] == null) {
				toOutputString.append("null");
				needSep = true;
				continue;
			}
			toOutputString.append('\'');
			// Actually do the DNS request for the member Peer of localHandshakeIPs
			toOutputString.append(localHandshakeIPs[i].getAddress(false));
			toOutputString.append('\'');
			needSep = true;
		}
		toOutputString.append(" ]");
		return toOutputString.toString();
	}

	/**
	* Do the maybeUpdateHandshakeIPs DNS requests, but only if ignoreHostnames is false
	* This method should only be called by maybeUpdateHandshakeIPs.
	* Also removes dupes post-lookup.
	*/
	private Peer[] updateHandshakeIPs(Peer[] localHandshakeIPs, boolean ignoreHostnames) {
		for(int i = 0; i < localHandshakeIPs.length; i++) {
			if(ignoreHostnames) {
				// Don't do a DNS request on the first cycle through PeerNodes by DNSRequest
				// upon startup (I suspect the following won't do anything, but just in case)
				if(logMINOR)
					Logger.debug(this, "updateHandshakeIPs: calling getAddress(false) on Peer '" + localHandshakeIPs[i] + "' for " + shortToString() + " (" + ignoreHostnames + ')');
				localHandshakeIPs[i].getAddress(false);
			} else {
				// Actually do the DNS request for the member Peer of localHandshakeIPs
				if(logMINOR)
					Logger.debug(this, "updateHandshakeIPs: calling getHandshakeAddress() on Peer '" + localHandshakeIPs[i] + "' for " + shortToString() + " (" + ignoreHostnames + ')');
				localHandshakeIPs[i].getHandshakeAddress();
			}
		}
		// De-dupe
		HashSet<Peer> ret = new HashSet<Peer>();
		for(int i = 0; i < localHandshakeIPs.length; i++)
			ret.add(localHandshakeIPs[i]);
		return ret.toArray(new Peer[ret.size()]);
	}

	/**
	* Do occasional DNS requests, but ignoreHostnames should be true
	* on PeerNode construction
	*/
	public void maybeUpdateHandshakeIPs(boolean ignoreHostnames) {
		long now = System.currentTimeMillis();
		Peer localDetectedPeer = null;
		synchronized(this) {
			localDetectedPeer = detectedPeer;
			if((now - lastAttemptedHandshakeIPUpdateTime) < (5 * 60 * 1000)) {  // 5 minutes
				//Logger.minor(this, "Looked up recently (localDetectedPeer = "+localDetectedPeer + " : "+((localDetectedPeer == null) ? "" : localDetectedPeer.getAddress(false).toString()));
				return;
			}
			// We want to come back right away for DNS requesting if this is our first time through
			if(!ignoreHostnames)
				lastAttemptedHandshakeIPUpdateTime = now;
		}
		if(logMINOR)
			Logger.minor(this, "Updating handshake IPs for peer '" + shortToString() + "' (" + ignoreHostnames + ')');
		Peer[] myNominalPeer;

		// Don't synchronize while doing lookups which may take a long time!
		synchronized(this) {
			myNominalPeer = nominalPeer.toArray(new Peer[nominalPeer.size()]);
		}

		Peer[] localHandshakeIPs;
		if(myNominalPeer.length == 0) {
			if(localDetectedPeer == null) {
				synchronized(this) {
					handshakeIPs = null;
				}
				if(logMINOR)
					Logger.minor(this, "1: maybeUpdateHandshakeIPs got a result of: " + handshakeIPsToString());
				return;
			}
			localHandshakeIPs = new Peer[]{localDetectedPeer};
			localHandshakeIPs = updateHandshakeIPs(localHandshakeIPs, ignoreHostnames);
			synchronized(this) {
				handshakeIPs = localHandshakeIPs;
			}
			if(logMINOR)
				Logger.minor(this, "2: maybeUpdateHandshakeIPs got a result of: " + handshakeIPsToString());
			return;
		}

		// Hack for two nodes on the same IP that can't talk over inet for routing reasons
		FreenetInetAddress localhost = node.fLocalhostAddress;
		Peer[] nodePeers = outgoingMangler.getPrimaryIPAddress();

		Vector<Peer> localPeers = null;
		synchronized(this) {
			localPeers = new Vector<Peer>(nominalPeer);
		}

		boolean addedLocalhost = false;
		Peer detectedDuplicate = null;
		for(int i = 0; i < myNominalPeer.length; i++) {
			Peer p = myNominalPeer[i];
			if(p == null)
				continue;
			if(localDetectedPeer != null) {
				if((p != localDetectedPeer) && p.equals(localDetectedPeer)) {
					// Equal but not the same object; need to update the copy.
					detectedDuplicate = p;
				}
			}
			FreenetInetAddress addr = p.getFreenetAddress();
			if(addr.equals(localhost)) {
				if(addedLocalhost)
					continue;
				addedLocalhost = true;
			}
			for(int j = 0; j < nodePeers.length; j++) {
				// REDFLAG - Two lines so we can see which variable is null when it NPEs
				FreenetInetAddress myAddr = nodePeers[j].getFreenetAddress();
				if(myAddr.equals(addr)) {
					if(!addedLocalhost)
						localPeers.add(new Peer(localhost, p.getPort()));
					addedLocalhost = true;
				}
			}
			if(localPeers.contains(p))
				continue;
			localPeers.add(p);
		}

		localHandshakeIPs = localPeers.toArray(new Peer[localPeers.size()]);
		localHandshakeIPs = updateHandshakeIPs(localHandshakeIPs, ignoreHostnames);
		synchronized(this) {
			handshakeIPs = localHandshakeIPs;
			if((detectedDuplicate != null) && detectedDuplicate.equals(localDetectedPeer))
				localDetectedPeer = detectedPeer = detectedDuplicate;
			updateShortToString();
		}
		if(logMINOR) {
			if(localDetectedPeer != null)
				Logger.minor(this, "3: detectedPeer = " + localDetectedPeer + " (" + localDetectedPeer.getAddress(false) + ')');
			Logger.minor(this, "3: maybeUpdateHandshakeIPs got a result of: " + handshakeIPsToString());
		}
	}

	/**
	* Returns this peer's current keyspace location, or -1 if it is unknown.
	*/
	public synchronized double getLocation() {
		return currentLocation;
	}

	public boolean shouldBeExcludedFromPeerList() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(BLACK_MAGIC_BACKOFF_PRUNING_PERCENTAGE < backedOffPercent.currentValue())
				return true;
			else if(BLACK_MAGIC_BACKOFF_PRUNING_TIME + now < getRoutingBackedOffUntilMax())
				return true;
			else
				return false;
		}
	}

	public synchronized  double[] getPeersLocation() {
		return currentPeersLocation;
	}

	public synchronized long getLocSetTime() {
		return locSetTime;
	}

	/**
	* Returns a unique node identifier (usefull to compare two peernodes).
	*/
	public int getIdentityHash() {
		return hashCode;
	}

	/**
	 * Returns true if the last-known build number for this peer is to old to allow traffic to be routed to it.
	 * This does not give any indication as to the connection status of the peer.
	 */
	public synchronized boolean isUnroutableOlderVersion() {
		return unroutableOlderVersion;
	}

	/**
	 * Returns true if this (or another) peer has reported to us that our build number is too old for data to be routed
	 * to us. In turn, we will not route data to them either. Does not strictly indicate that the peer is connected.
	 */
	public synchronized boolean isUnroutableNewerVersion() {
		return unroutableNewerVersion;
	}

	/**
	* Returns true if requests can be routed through this peer. True if the peer's location is known, presently
	* connected, and routing-compatible. That is, ignoring backoff, the peer's location is known, build number
	* is compatible, and routing has not been explicitly disabled.
	*
	* Note possible deadlocks! PeerManager calls this, we call
	* PeerManager in e.g. verified.
	*/
	public boolean isRoutable() {
		return isConnected() && isRoutingCompatible() &&
			!(currentLocation < 0.0 || currentLocation > 1.0);
	}

	/**
	 * Returns true if (apart from actually knowing the peer's location), it is presumed that this peer could route requests.
	 * True if this peer's build number is not 'too-old' or 'too-new', actively connected, and not marked as explicity disabled.
	 * Does not reflect any 'backoff' logic.
	 */
	public boolean isRoutingCompatible() {
		long now = System.currentTimeMillis(); // no System.currentTimeMillis in synchronized
		synchronized(this) {
			if(isRoutable && !disableRouting) {
				timeLastRoutable = now;
				return true;
			}
			return false;
		}
	}

	public boolean isConnected() {
		long now = System.currentTimeMillis(); // no System.currentTimeMillis in synchronized
		synchronized(this) {
			if(isConnected && currentTracker != null && !currentTracker.packets.isDeprecated()) {
				timeLastConnected = now;
				return true;
			}
			return false;
		}
	}

	/**
	* Send a message, off-thread, to this node.
	* @param msg The message to be sent.
	* @param cb The callback to be called when the packet has been sent, or null.
	* @param ctr A callback to tell how many bytes were used to send this message.
	*/
	public MessageItem sendAsync(Message msg, AsyncMessageCallback cb, ByteCounter ctr) throws NotConnectedException {
		if(ctr == null)
			Logger.error(this, "Bytes not logged", new Exception("debug"));
		if(logMINOR)
			Logger.minor(this, "Sending async: " + msg + " : " + cb + " on " + this+" for "+node.getDarknetPortNumber()+" priority "+msg.getPriority());
		if(!isConnected()) {
			if(cb != null)
				cb.disconnected();
			throw new NotConnectedException();
		}
		addToLocalNodeSentMessagesToStatistic(msg);
		MessageItem item = new MessageItem(msg, cb == null ? null : new AsyncMessageCallback[]{cb}, ctr);
		long now = System.currentTimeMillis();
		reportBackoffStatus(now);
		int maxSize = getMaxPacketSize();
		int x = messageQueue.queueAndEstimateSize(item, maxSize);
		if(x > maxSize || !node.enablePacketCoalescing) {
			// If there is a packet's worth to send, wake up the packetsender.
			wakeUpSender();
		}
		// Otherwise we do not need to wake up the PacketSender
		// It will wake up before the maximum coalescing delay (100ms) because
		// it wakes up every 100ms *anyway*.
		return item;
	}
	
	public void wakeUpSender() {
		if(logMINOR) Logger.minor(this, "Waking up PacketSender");
		node.ps.wakeUp();
	}

	public boolean unqueueMessage(MessageItem message) {
		if(logMINOR) Logger.minor(this, "Unqueueing message on "+this+" : "+message);
		return messageQueue.removeMessage(message);
	}

	public long getMessageQueueLengthBytes() {
		return messageQueue.getMessageQueueLengthBytes();
	}

	/**
	 * Returns the number of milliseconds that it is estimated to take to transmit the currently queued packets.
	 */
	public long getProbableSendQueueTime() {
		double bandwidth = (getThrottle().getBandwidth()+1.0);
		if(shouldThrottle())
			bandwidth = Math.min(bandwidth, node.getOutputBandwidthLimit() / 2);
		long length = getMessageQueueLengthBytes();
		return (long)(1000.0*length/bandwidth);
	}

	/**
	* @return The last time we received a packet.
	*/
	public synchronized long lastReceivedPacketTime() {
		return timeLastReceivedPacket;
	}

	public synchronized long lastReceivedDataPacketTime() {
		return timeLastReceivedDataPacket;
	}
	
	public synchronized long lastReceivedAckTime() {
		return timeLastReceivedAck;
	}

	public synchronized long timeLastConnected() {
		return timeLastConnected;
	}

	public synchronized long timeLastRoutable() {
		return timeLastRoutable;
	}

	public void maybeRekey() {
		long now = System.currentTimeMillis();
		boolean shouldDisconnect = false;
		boolean shouldReturn = false;
		boolean shouldRekey = false;
		long timeWhenRekeyingShouldOccur = 0;

		synchronized (this) {
			timeWhenRekeyingShouldOccur = timeLastRekeyed + FNPPacketMangler.SESSION_KEY_REKEYING_INTERVAL;
			shouldDisconnect = (timeWhenRekeyingShouldOccur + FNPPacketMangler.MAX_SESSION_KEY_REKEYING_DELAY < now) && isRekeying;
			shouldReturn = isRekeying || !isConnected;
			shouldRekey = (timeWhenRekeyingShouldOccur < now);
			if((!shouldRekey) && totalBytesExchangedWithCurrentTracker > FNPPacketMangler.AMOUNT_OF_BYTES_ALLOWED_BEFORE_WE_REKEY) {
				shouldRekey = true;
				timeWhenRekeyingShouldOccur = now;
			}
		}

		if(shouldDisconnect) {
			String time = TimeUtil.formatTime(FNPPacketMangler.MAX_SESSION_KEY_REKEYING_DELAY);
			System.err.println("The peer (" + this + ") has been asked to rekey " + time + " ago... force disconnect.");
			Logger.error(this, "The peer (" + this + ") has been asked to rekey " + time + " ago... force disconnect.");
			forceDisconnect(false);
		} else if (shouldReturn || hasLiveHandshake(now)) {
			return;
		} else if(shouldRekey) {
			startRekeying();
		}
	}

	public void startRekeying() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(isRekeying) return;
			isRekeying = true;
			sendHandshakeTime = now; // Immediately
			ctx = null;
		}
		Logger.normal(this, "We are asking for the key to be renewed (" + this.detectedPeer + ')');
	}

	/**
	* @return The time this PeerNode was added to the node (persistent across restarts).
	*/
	public synchronized long getPeerAddedTime() {
		return peerAddedTime;
	}

	/**
	* @return The time elapsed since this PeerNode was added to the node, or the node started up.
	*/
	public synchronized long timeSinceAddedOrRestarted() {
		return System.currentTimeMillis() - timeAddedOrRestarted;
	}
	
	/**
	* Disconnected e.g. due to not receiving a packet for ages.
	* @param dumpMessageQueue If true, clear the messages-to-send queue.
	* @param dumpTrackers If true, dump the SessionKey's.
	* @return True if the node was connected, false if it was not.
	*/
	public boolean disconnected(boolean dumpMessageQueue, boolean dumpTrackers) {
		final long now = System.currentTimeMillis();
		if(isRealConnection())
			Logger.normal(this, "Disconnected " + this, new Exception("debug"));
		else if(logMINOR)
			Logger.minor(this, "Disconnected "+this, new Exception("debug"));
		node.usm.onDisconnect(this);
		node.failureTable.onDisconnect(this);
		node.peers.disconnected(this);
		node.nodeUpdater.disconnected(this);
		boolean ret;
		SessionKey cur, prev, unv;
		MessageItem[] messagesTellDisconnected = null;
		List<MessageItem> moreMessagesTellDisconnected = null;
		PacketFormat oldPacketFormat = null;
		synchronized(this) {
			disconnecting = false;
			ret = isConnected;
			// Force renegotiation.
			isConnected = false;
			isRoutable = false;
			isRekeying = false;
			// Prevent sending packets to the node until that happens.
			cur = currentTracker;
			prev = previousTracker;
			unv = unverifiedTracker;
			if(dumpTrackers) {
				currentTracker = null;
				previousTracker = null;
				unverifiedTracker = null;
			}
			// Else DO NOT clear trackers, because hopefully it's a temporary connectivity glitch.
			sendHandshakeTime = now;
			countFailedRevocationTransfers = 0;
			timePrevDisconnect = timeLastDisconnect;
			timeLastDisconnect = now;
			if(dumpMessageQueue) {
				// Reset the boot ID so that we get different trackers next time.
				myBootID = node.fastWeakRandom.nextLong();
				messagesTellDisconnected = grabQueuedMessageItems();
				oldPacketFormat = packetFormat;
				packetFormat = null;
			}
		}
		if(oldPacketFormat != null) {
			moreMessagesTellDisconnected = oldPacketFormat.onDisconnect();
		}
		if(messagesTellDisconnected != null) {
			if(logMINOR)
				Logger.minor(this, "Messages to dump: "+messagesTellDisconnected.length);
			for(MessageItem mi : messagesTellDisconnected) {
				mi.onDisconnect();
			}
		}
		if(moreMessagesTellDisconnected != null) {
			if(logMINOR)
				Logger.minor(this, "Messages to dump: "+moreMessagesTellDisconnected.size());
			for(MessageItem mi : moreMessagesTellDisconnected) {
				mi.onDisconnect();
			}
		}
		if(cur != null) cur.disconnected(false);
		if(prev != null) prev.disconnected(false);
		if(unv != null) unv.disconnected(false);
		if(_lastThrottle != null)
			_lastThrottle.maybeDisconnected();
		node.lm.lostOrRestartedNode(this);
		if(peers.havePeer(this))
			setPeerNodeStatus(now);
		if(!dumpMessageQueue) {
			node.getTicker().queueTimedJob(new Runnable() {
				public void run() {
					if((!PeerNode.this.isConnected()) &&
							timeLastDisconnect == now) {
						PacketFormat oldPacketFormat = null;
						synchronized(this) {
							if(!isConnected) return;
							// Reset the boot ID so that we get different trackers next time.
							myBootID = node.fastWeakRandom.nextLong();
							oldPacketFormat = packetFormat;
							packetFormat = null;
						}
						MessageItem[] messagesTellDisconnected = grabQueuedMessageItems();
						if(messagesTellDisconnected != null) {
							for(MessageItem mi : messagesTellDisconnected) {
								mi.onDisconnect();
							}
						}
						if(oldPacketFormat != null) {
							List<MessageItem> moreMessagesTellDisconnected = 
								oldPacketFormat.onDisconnect();
							if(moreMessagesTellDisconnected != null) {
								if(logMINOR)
									Logger.minor(this, "Messages to dump: "+moreMessagesTellDisconnected.size());
								for(MessageItem mi : moreMessagesTellDisconnected) {
									mi.onDisconnect();
								}
							}
						}
					}

				}
			}, CLEAR_MESSAGE_QUEUE_AFTER);
		}
		// Tell opennet manager even if this is darknet, because we may need more opennet peers now.
		OpennetManager om = node.getOpennet();
		if(om != null)
			om.onDisconnect(this);
		outputLoadTrackerRealTime.failSlotWaiters(true);
		outputLoadTrackerBulk.failSlotWaiters(true);
		loadSenderBulk.onDisconnect();
		loadSenderRealTime.onDisconnect();
		return ret;
	}

	public void forceDisconnect(boolean purge) {
		Logger.error(this, "Forcing disconnect on " + this, new Exception("debug"));
		disconnected(purge, true); // always dump trackers, maybe dump messages
	}

	/**
	* Grab all queued Message's.
	* @return Null if no messages are queued, or an array of
	* Message's.
	*/
	public MessageItem[] grabQueuedMessageItems() {
		return messageQueue.grabQueuedMessageItems();
	}

	public void requeueMessageItems(MessageItem[] messages, int offset, int length, boolean dontLog) {
		requeueMessageItems(messages, offset, length, dontLog, "");
	}

	public void requeueMessageItems(MessageItem[] messages, int offset, int length, boolean dontLog, String reason) {
		// Will usually indicate serious problems
		if(!dontLog) {
			long now = System.currentTimeMillis();
			String rateLimitWrapper = "";
			boolean rateLimitLogging = false;
			if(messages.length > messageRequeueLogRateLimitThreshold) {
				rateLimitWrapper = " (log message rate limited)";
				if(nextMessageRequeueLogTime <= now) {
					nextMessageRequeueLogTime = now + messageRequeueLogRateLimitInterval;
				} else {
					rateLimitLogging = true;
				}
			}
			if(!rateLimitLogging) {
				String reasonWrapper = "";
				if(0 <= reason.length()) {
					reasonWrapper = " because of '" + reason + '\'';
				}
				Logger.normal(this, "Requeueing " + messages.length + " messages" + reasonWrapper + " on " + this + rateLimitWrapper);
			}
		}
		synchronized(messageQueue) {
			for(int i = offset+length-1; i >= offset; i--)
				if(messages[i] != null)
					messageQueue.pushfrontPrioritizedMessageItem(messages[i]);
		}
	}

	/**
	* @return The time at which we must send a packet, even if
	* it means it will only contains ack requests etc., or
	* Long.MAX_VALUE if we have no pending ack request/acks/etc.
	* Note that if this is less than now, it may not be entirely
	* accurate i.e. we definitely must send a packet, but don't
	* rely on it to tell you exactly how overdue we are.
	*/
	public long getNextUrgentTime(long now) {
		long t = Long.MAX_VALUE;
		SessionKey cur;
		SessionKey prev;
		PacketFormat pf;
		synchronized(this) {
			if(!isConnected) return Long.MAX_VALUE;
			cur = currentTracker;
			prev = previousTracker;
			pf = packetFormat;
			if(cur == null && prev == null) return Long.MAX_VALUE;
		}
		SessionKey kt = cur;
		if(kt != null) {
			long next = kt.packets.getNextUrgentTime();
			t = Math.min(t, next);
			if(next < now && logMINOR)
				Logger.minor(this, "Next urgent time from curTracker less than now");
			if(kt.packets.hasPacketsToResend()) {
				// We could use the original packet send time, but I don't think it matters that much: Old peers with heavy packet loss are probably going to have problems anyway...
				return now;
			}
		}
		kt = prev;
		if(kt != null) {
			long next = kt.packets.getNextUrgentTime();
			t = Math.min(t, next);
			if(next < now && logMINOR)
				Logger.minor(this, "Next urgent time from prevTracker less than now");
			if(kt.packets.hasPacketsToResend()) {
				// We could use the original packet send time, but I don't think it matters that much: Old peers with heavy packet loss are probably going to have problems anyway...
				return now;
			}
		}
		if(pf != null) {
			boolean canSend = cur != null && pf.canSend(cur);
			if(canSend) { // New messages are only sent on cur.
				long l = messageQueue.getNextUrgentTime(t, 0); // Need an accurate value even if in the past.
				if(t >= now && l < now && logMINOR)
					Logger.minor(this, "Next urgent time from message queue less than now");
				else if(logDEBUG)
					Logger.debug(this, "Next urgent time is "+(l-now)+"ms on "+this);
				t = l;
			}
			long l = pf.timeNextUrgent(canSend);
			if(l < now && logMINOR)
				Logger.minor(this, "Next urgent time from packet format less than now on "+this);
			t = Math.min(t, l);
		}
		return t;
	}

	/**
	* @return The time at which we last sent a packet.
	*/
	public long lastSentPacketTime() {
		return timeLastSentPacket;
	}

	/**
	* @return True, if we are disconnected and it has been a
	* sufficient time period since we last sent a handshake
	* attempt.
	*/
	public boolean shouldSendHandshake() {
		long now = System.currentTimeMillis();
		boolean tempShouldSendHandshake = false;
		synchronized(this) {
			if(disconnecting) return false;
			tempShouldSendHandshake = ((now > sendHandshakeTime) && (handshakeIPs != null) && (isRekeying || !isConnected()));
		}
		if(logMINOR) Logger.minor(this, "shouldSendHandshake(): initial = "+tempShouldSendHandshake);
		if(tempShouldSendHandshake && (hasLiveHandshake(now)))
			tempShouldSendHandshake = false;
		if(tempShouldSendHandshake) {
			if(isBurstOnly()) {
				synchronized(this) {
					isBursting = true;
				}
				setPeerNodeStatus(System.currentTimeMillis());
			} else
				return true;
		}
		if(logMINOR) Logger.minor(this, "shouldSendHandshake(): final = "+tempShouldSendHandshake);
		return tempShouldSendHandshake;
	}
	
	public long timeSendHandshake(long now) {
		if(hasLiveHandshake(now)) return Long.MAX_VALUE;
		synchronized(this) {
			if(disconnecting) return Long.MAX_VALUE;
			if(handshakeIPs == null) return Long.MAX_VALUE;
			if(!(isRekeying || !isConnected())) return Long.MAX_VALUE;
			return sendHandshakeTime;
		}
	}

	/**
	* Does the node have a live handshake in progress?
	* @param now The current time.
	*/
	public boolean hasLiveHandshake(long now) {
		KeyAgreementSchemeContext c = null;
		synchronized(this) {
			c = ctx;
		}
		if(c != null && logDEBUG)
			Logger.minor(this, "Last used (handshake): " + (now - c.lastUsedTime()));
		return !((c == null) || (now - c.lastUsedTime() > Node.HANDSHAKE_TIMEOUT));
	}
	boolean firstHandshake = true;

	/**
	 * Set sendHandshakeTime, and return whether to fetch the ARK.
	 */
	protected boolean innerCalcNextHandshake(boolean successfulHandshakeSend, boolean dontFetchARK, long now) {
		if(isBurstOnly())
			return calcNextHandshakeBurstOnly(now);
		synchronized(this) {
			long delay;
			if(unroutableOlderVersion || unroutableNewerVersion || disableRouting) {
				// Let them know we're here, but have no hope of routing general data to them.
				delay = Node.MIN_TIME_BETWEEN_VERSION_SENDS + node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_VERSION_SENDS);
			} else if(invalidVersion() && !firstHandshake) {
				delay = Node.MIN_TIME_BETWEEN_VERSION_PROBES + node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_VERSION_PROBES);
			} else {
				delay = Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS + node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
			}
			// FIXME proper multi-homing support!
			delay /= (handshakeIPs == null ? 1 : handshakeIPs.length);
			if(delay < 3000) delay = 3000;
			sendHandshakeTime = now + delay;
			if(logMINOR) Logger.minor(this, "Next handshake in "+delay+" on "+this);

			if(successfulHandshakeSend)
				firstHandshake = false;
			handshakeCount++;
			return handshakeCount == MAX_HANDSHAKE_COUNT;
		}
	}

	private synchronized boolean calcNextHandshakeBurstOnly(long now) {
		boolean fetchARKFlag = false;
		listeningHandshakeBurstCount++;
		if(isBurstOnly()) {
			if(listeningHandshakeBurstCount >= listeningHandshakeBurstSize) {
				listeningHandshakeBurstCount = 0;
				fetchARKFlag = true;
			}
		}
		long delay;
		if(listeningHandshakeBurstCount == 0) {  // 0 only if we just reset it above
			delay = Node.MIN_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS
				+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS);
			listeningHandshakeBurstSize = Node.MIN_BURSTING_HANDSHAKE_BURST_SIZE
					+ node.random.nextInt(Node.RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE);
			isBursting = false;
		} else {
			delay = Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS
				+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
		}
		// FIXME proper multi-homing support!
		delay /= (handshakeIPs == null ? 1 : handshakeIPs.length);
		if(delay < 3000) delay = 3000;

		sendHandshakeTime = now + delay;
		if(logMINOR) Logger.minor(this, "Next BurstOnly mode handshake in "+(sendHandshakeTime - now)+"ms for "+shortToString()+" (count: "+listeningHandshakeBurstCount+", size: "+listeningHandshakeBurstSize+ ") on "+this, new Exception("double-called debug"));
		return fetchARKFlag;
	}

	protected void calcNextHandshake(boolean successfulHandshakeSend, boolean dontFetchARK, boolean notRegistered) {
		long now = System.currentTimeMillis();
		boolean fetchARKFlag = false;
		fetchARKFlag = innerCalcNextHandshake(successfulHandshakeSend, dontFetchARK, now);
		if(!notRegistered)
			setPeerNodeStatus(now);  // Because of isBursting being set above and it can't hurt others
		// Don't fetch ARKs for peers we have verified (through handshake) to be incompatible with us
		if(fetchARKFlag && !dontFetchARK) {
			long arkFetcherStartTime1 = System.currentTimeMillis();
			startARKFetcher();
			long arkFetcherStartTime2 = System.currentTimeMillis();
			if((arkFetcherStartTime2 - arkFetcherStartTime1) > 500)
				Logger.normal(this, "arkFetcherStartTime2 is more than half a second after arkFetcherStartTime1 (" + (arkFetcherStartTime2 - arkFetcherStartTime1) + ") working on " + shortToString());
		}
	}

	/** If the outgoingMangler allows bursting, we still don't want to burst *all the time*, because it may be mistaken
	 * in its detection of a port forward. So from time to time we will aggressively handshake anyway. This flag is set
	 * once every UPDATE_BURST_NOW_PERIOD. */
	private boolean burstNow;
	private long timeSetBurstNow;
	static final int UPDATE_BURST_NOW_PERIOD = 5*60*1000;
	/** Burst only 19 in 20 times if definitely port forwarded. Save entropy by writing this as 20 not 0.95. */
	static final int P_BURST_IF_DEFINITELY_FORWARDED = 20;

	public boolean isBurstOnly() {
		AddressTracker.Status status = outgoingMangler.getConnectivityStatus();
		if(status == AddressTracker.Status.DONT_KNOW) return false;
		if(status == AddressTracker.Status.DEFINITELY_NATED || status == AddressTracker.Status.MAYBE_NATED) return false;

		// For now. FIXME try it with a lower probability when we're sure that the packet-deltas mechanisms works.
		if(status == AddressTracker.Status.MAYBE_PORT_FORWARDED) return false;
		long now = System.currentTimeMillis();
		if(now - timeSetBurstNow > UPDATE_BURST_NOW_PERIOD) {
			burstNow = (node.random.nextInt(P_BURST_IF_DEFINITELY_FORWARDED) == 0);
			timeSetBurstNow = now;
		}
		return burstNow;
	}

	/**
	* Call this method when a handshake request has been
	* sent.
	*/
	public void sentHandshake(boolean notRegistered) {
		if(logMINOR)
			Logger.minor(this, "sentHandshake(): " + this);
		calcNextHandshake(true, false, notRegistered);
	}

	/**
	* Call this method when a handshake request could not be sent (i.e. no IP address available)
	* sent.
	*/
	public void couldNotSendHandshake(boolean notRegistered) {
		if(logMINOR)
			Logger.minor(this, "couldNotSendHandshake(): " + this);
		calcNextHandshake(false, false, notRegistered);
	}

	/**
	* @return The maximum time between received packets.
	*/
	public int maxTimeBetweenReceivedPackets() {
		return Node.MAX_PEER_INACTIVITY;
	}

	/**
	* @return The maximum time between received packets.
	*/
	public int maxTimeBetweenReceivedAcks() {
		return Node.MAX_PEER_INACTIVITY;
	}

	/**
	* Low-level ping this node.
	* @return True if we received a reply inside 2000ms.
	* (If we have heavy packet loss, it can take that long to resend).
	*/
	public boolean ping(int pingID) throws NotConnectedException {
		Message ping = DMT.createFNPPing(pingID);
		node.usm.send(this, ping, node.dispatcher.pingCounter);
		Message msg;
		try {
			msg = node.usm.waitFor(MessageFilter.create().setTimeout(2000).setType(DMT.FNPPong).setField(DMT.PING_SEQNO, pingID), null);
		} catch(DisconnectedException e) {
			throw new NotConnectedException("Disconnected while waiting for pong");
		}
		return msg != null;
	}

	/**
	* Decrement the HTL (or not), in accordance with our
	* probabilistic HTL rules.
	* @param htl The old HTL.
	* @return The new HTL.
	*/
	public short decrementHTL(short htl) {
		short max = node.maxHTL();
		if(htl > max)
			htl = max;
		if(htl <= 0)
			return 0;
		if(htl == max) {
			if(decrementHTLAtMaximum || node.disableProbabilisticHTLs)
				htl--;
			return htl;
		}
		if(htl == 1) {
			if(decrementHTLAtMinimum || node.disableProbabilisticHTLs)
				htl--;
			return htl;
		}
		htl--;
		return htl;
	}

	/**
	* Enqueue a message to be sent to this node and wait up to a minute for it to be transmitted
	* and acknowledged.
	*/
	public void sendSync(Message req, ByteCounter ctr, boolean realTime) throws NotConnectedException, SyncSendWaitedTooLongException {
		SyncMessageCallback cb = new SyncMessageCallback();
		MessageItem item = sendAsync(req, cb, ctr);
		cb.waitForSend(60 * 1000);
		if (!cb.done) {
			Logger.warning(this, "Waited too long for a blocking send for " + req + " to " + PeerNode.this, new Exception("error"));
			this.localRejectedOverload("SendSyncTimeout", realTime);
			// Try to unqueue it, since it presumably won't be of any use now.
			if(!messageQueue.removeMessage(item)) {
				cb.waitForSend(10 * 1000);
				if(!cb.done) {
					Logger.error(this, "Waited too long for blocking send and then could not unqueue for "+req+" to "+PeerNode.this, new Exception("error"));
					// Can't cancel yet can't send, something seriously wrong.
					// Treat as fatal timeout as probably their fault.
					// FIXME: We have already waited more than the no-messages timeout, but should we wait that period again???
					fatalTimeout();
					// Then throw the error.
				} else {
					return;
				}
			}
			throw new SyncSendWaitedTooLongException();
		}
	}

	private class SyncMessageCallback implements AsyncMessageCallback {

		private boolean done = false;
		private boolean disconnected = false;

		public synchronized void waitForSend(long maxWaitInterval) throws NotConnectedException {
			long now = System.currentTimeMillis();
			long end = now + maxWaitInterval;
			while((now = System.currentTimeMillis()) < end) {
				if(done) {
					if(disconnected)
						throw new NotConnectedException();
					return;
				}
				int waitTime = (int) (Math.min(end - now, Integer.MAX_VALUE));
				try {
					wait(waitTime);
				} catch(InterruptedException e) {
				// Ignore
				}
			}
		}

		public void acknowledged() {
			synchronized(this) {
				if(!done)
					// Can happen due to lag.
					Logger.normal(this, "Acknowledged but not sent?! on " + this + " for " + PeerNode.this+" - lag ???");
				else
					return;
				done = true;
				notifyAll();
			}
		}

		public void disconnected() {
			synchronized(this) {
				done = true;
				disconnected = true;
				notifyAll();
			}
		}

		public void fatalError() {
			synchronized(this) {
				done = true;
				notifyAll();
			}
		}

		public void sent() {
			// Ignore.
			// It might have been lost, we wait until it is acked.
		}
	}

	public void updateLocation(double newLoc, double[] newLocs) {
		if(newLoc < 0.0 || newLoc > 1.0) {
			Logger.error(this, "Invalid location update for " + this+ " ("+newLoc+')', new Exception("error"));
			// Ignore it
			return;
		}

		for(double currentLoc : newLocs) {
			if(currentLoc < 0.0 || currentLoc > 1.0) {
				Logger.error(this, "Invalid location update for " + this + " ("+currentLoc+')', new Exception("error"));
				// Ignore it
				return;
			}
		}

		Arrays.sort(newLocs);

		synchronized(this) {
			currentLocation = newLoc;
			currentPeersLocation = newLocs;
			locSetTime = System.currentTimeMillis();
		}
		node.peers.writePeers();
		setPeerNodeStatus(System.currentTimeMillis());
	}

	/**
	* Should we reject a swap request?
	*/
	public boolean shouldRejectSwapRequest() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(timeLastReceivedSwapRequest > 0) {
				long timeSinceLastTime = now - timeLastReceivedSwapRequest;
				swapRequestsInterval.report(timeSinceLastTime);
				double averageInterval = swapRequestsInterval.currentValue();
				if(averageInterval >= Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS) {
					timeLastReceivedSwapRequest = now;
					return false;
				} else return true;
			}
			timeLastReceivedSwapRequest = now;
		}
		return false;
	}

	/**
	* Should we reject a swap request?
	*/
	public boolean shouldRejectProbeRequest() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(timeLastReceivedProbeRequest > 0) {
				long timeSinceLastTime = now - timeLastReceivedProbeRequest;
				probeRequestsInterval.report(timeSinceLastTime);
				double averageInterval = probeRequestsInterval.currentValue();
				if(averageInterval >= Node.MIN_INTERVAL_BETWEEN_INCOMING_PROBE_REQUESTS) {
					timeLastReceivedProbeRequest = now;
					return false;
				} else return true;
			}
			timeLastReceivedProbeRequest = now;
		}
		return false;
	}

	/**
	* IP on the other side appears to have changed...
	* @param newPeer The new address of the peer.
	*/
	public void changedIP(Peer newPeer) {
		setDetectedPeer(newPeer);
	}

	private void setDetectedPeer(Peer newPeer) {
		// Only clear lastAttemptedHandshakeIPUpdateTime if we have a new IP.
		// Also, we need to call .equals() to propagate any DNS lookups that have been done if the two have the same domain.
		Peer p = newPeer;
		newPeer = newPeer.dropHostName();
		if(newPeer == null) {
			Logger.error(this, "Impossible: No address for detected peer! "+p+" on "+this);
			return;
		}
		synchronized(this) {
			Peer oldPeer = detectedPeer;
			if((newPeer != null) && ((oldPeer == null) || !oldPeer.equals(newPeer))) {
				this.detectedPeer = newPeer;
				updateShortToString();
				this.lastAttemptedHandshakeIPUpdateTime = 0;
				if(!isConnected)
					return;
			} else
				return;
		}
		getThrottle().maybeDisconnected();
		sendIPAddressMessage();
	}

	/**
	* @return The current primary SessionKey, or null if we
	* don't have one.
	*/
	public synchronized SessionKey getCurrentKeyTracker() {
		return currentTracker;
	}

	/**
	* @return The previous primary SessionKey, or null if we
	* don't have one.
	*/
	public synchronized SessionKey getPreviousKeyTracker() {
		return previousTracker;
	}

	/**
	* @return The unverified SessionKey, if any, or null if we
	* don't have one. The caller MUST call verified(KT) if a
	* decrypt succeeds with this KT.
	*/
	public synchronized SessionKey getUnverifiedKeyTracker() {
		return unverifiedTracker;
	}

	private String shortToString;
	private void updateShortToString() {
		shortToString = super.toString() + '@' + detectedPeer + '@' + HexUtil.bytesToHex(identity);
	}

	/**
	* @return short version of toString()
	* *** Note that this is not synchronized! It is used by logging in code paths that
	* will deadlock if it is synchronized! ***
	*/
	public String shortToString() {
		return shortToString;
	}

	@Override
	public String toString() {
		// FIXME?
		return shortToString()+'@'+Integer.toHexString(super.hashCode());
	}

	/**
	* Update timeLastReceivedPacket
	* @throws NotConnectedException
	* @param dontLog If true, don't log an error or throw an exception if we are not connected. This
	* can be used in handshaking when the connection hasn't been verified yet.
	* @param dataPacket If this is a real packet, as opposed to a handshake packet.
	*/
	public void receivedPacket(boolean dontLog, boolean dataPacket) {
		synchronized(this) {
			if((!isConnected) && (!dontLog)) {
				// Don't log if we are disconnecting, because receiving packets during disconnecting is normal.
				// That includes receiving packets after we have technically disconnected already.
				// A race condition involving forceCancelDisconnecting causing a mistaken log message anyway
				// is conceivable, but unlikely...
				if((unverifiedTracker == null) && (currentTracker == null) && !disconnecting)
					Logger.error(this, "Received packet while disconnected!: " + this, new Exception("error"));
				else
					if(logMINOR)
						Logger.minor(this, "Received packet while disconnected on " + this + " - recently disconnected() ?");
			} else {
				if(logMINOR) Logger.minor(this, "Received packet on "+this);
			}
		}
		long now = System.currentTimeMillis();
		synchronized(this) {
			timeLastReceivedPacket = now;
			if(dataPacket)
				timeLastReceivedDataPacket = now;
		}
	}
	
	public synchronized void receivedAck(long now) {
		if(timeLastReceivedAck < now)
			timeLastReceivedAck = now;
	}

	/**
	* Update timeLastSentPacket
	*/
	public void sentPacket() {
		timeLastSentPacket = System.currentTimeMillis();
	}

	public synchronized KeyAgreementSchemeContext getKeyAgreementSchemeContext() {
		return ctx;
	}

	public synchronized void setKeyAgreementSchemeContext(KeyAgreementSchemeContext ctx2) {
		this.ctx = ctx2;
		if(logMINOR)
			Logger.minor(this, "setKeyAgreementSchemeContext(" + ctx2 + ") on " + this);
	}

	/**
	* Called when we have completed a handshake, and have a new session key.
	* Creates a new tracker and demotes the old one. Deletes the old one if
	* the bootID isn't recognized, since if the node has restarted we cannot
	* recover old messages. In more detail:
	* <ul>
	* <li>Process the new noderef (check if it's valid, pick up any new information etc).</li>
	* <li>Handle version conflicts (if the node is too old, or we are too old, we mark it as
	* non-routable, but some messages will still be exchanged e.g. Update Over Mandatory stuff).</li>
	* <li>Deal with key trackers (if we just got message 4, the new key tracker becomes current;
	* if we just got message 3, it's possible that our message 4 will be lost in transit, so we
	* make the new tracker unverified. It will be promoted to current if we get a packet on it..
	* if the node has restarted, we dump the old key trackers, otherwise current becomes previous).</li>
	* <li>Complete the connection process: update the node's status, send initial messages, update
	* the last-received-packet timestamp, etc.</li>
	* @param thisBootID The boot ID of the peer we have just connected to.
	* This is simply a random number regenerated on every startup of the node.
	* We use it to determine whether the node has restarted since we last saw
	* it.
	* @param data Byte array from which to read the new noderef.
	* @param offset Offset to start reading at.
	* @param length Number of bytes to read.
	* @param encKey The new session key.
	* @param replyTo The IP the handshake came in on.
	* @param trackerID The tracker ID proposed by the other side. If -1, create a new tracker. If any
	* other value, check whether we have it, and if we do, return that, otherwise return the ID of the
	* new tracker.
	* @param isJFK4 If true, we are processing a JFK(4) and must respect the tracker ID chosen by the
	* responder. If false, we are processing a JFK(3) and we can either reuse the suggested tracker ID,
	* which the other side is able to reuse, or we can create a new tracker ID.
	* @param jfk4SameAsOld If true, the responder chose to use the tracker ID that we provided. If
	* we don't have it now the connection fails.
	* @return The ID of the new PacketTracker. If this is different to the passed-in trackerID, then
	* it's a new tracker. -1 to indicate failure.
	*/
	public long completedHandshake(long thisBootID, byte[] data, int offset, int length, BlockCipher outgoingCipher, byte[] outgoingKey, BlockCipher incommingCipher, byte[] incommingKey, Peer replyTo, boolean unverified, int negType, long trackerID, boolean isJFK4, boolean jfk4SameAsOld, byte[] hmacKey, BlockCipher ivCipher, byte[] ivNonce, int ourInitialSeqNum, int theirInitialSeqNum, int ourInitialMsgID, int theirInitialMsgID) {
		long now = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Tracker ID "+trackerID+" isJFK4="+isJFK4+" jfk4SameAsOld="+jfk4SameAsOld);

		// Update sendHandshakeTime; don't send another handshake for a while.
		// If unverified, "a while" determines the timeout; if not, it's just good practice to avoid a race below.
		if(!(isSeed() && this instanceof SeedServerPeerNode))
                    calcNextHandshake(true, true, false);
		stopARKFetcher();
		try {
			// First, the new noderef
			processNewNoderef(data, offset, length);
		} catch(FSParseException e1) {
			synchronized(this) {
				bogusNoderef = true;
				// Disconnect, something broke
				isConnected = false;
			}
			Logger.error(this, "Failed to parse new noderef for " + this + ": " + e1, e1);
			node.peers.disconnected(this);
			return -1;
		}
		boolean routable = true;
		boolean newer = false;
		boolean older = false;
		if(isSeed()) {
                        routable = false;
                        if(logMINOR) Logger.minor(this, "Not routing traffic to " + this + " it's for announcement.");
                } else if(bogusNoderef) {
			Logger.normal(this, "Not routing traffic to " + this + " - bogus noderef");
			routable = false;
			//FIXME: It looks like bogusNoderef will just be set to false a few lines later...
		} else if(reverseInvalidVersion()) {
			try {
				node.setNewestPeerLastGoodVersion(Version.getArbitraryBuildNumber(getLastGoodVersion(), Version.lastGoodBuild()));
			} catch(NumberFormatException e) {
			// ignore
			}
			Logger.normal(this, "Not routing traffic to " + this + " - reverse invalid version " + Version.getVersionString() + " for peer's lastGoodversion: " + getLastGoodVersion());
			newer = true;
		} else
			newer = false;
		if(forwardInvalidVersion()) {
			Logger.normal(this, "Not routing traffic to " + this + " - invalid version " + getVersion());
			older = true;
			routable = false;
		} else if(Math.abs(clockDelta) > MAX_CLOCK_DELTA) {
			Logger.normal(this, "Not routing traffic to " + this + " - clock problems");
			routable = false;
		} else
			older = false;
		changedIP(replyTo);
		boolean bootIDChanged = false;
		boolean wasARekey = false;
		SessionKey oldPrev = null;
		SessionKey oldCur = null;
		SessionKey prev = null;
		SessionKey newTracker;
		MessageItem[] messagesTellDisconnected = null;
		PacketFormat oldPacketFormat = null;
		PacketTracker packets = null;
		synchronized(this) {
			disconnecting = false;
			// FIXME this shouldn't happen, does it?
			if(currentTracker != null) {
				if(Arrays.equals(outgoingKey, currentTracker.outgoingKey)
						&& Arrays.equals(incommingKey, currentTracker.incommingKey)) {
					Logger.error(this, "completedHandshake() with identical key to current, maybe replayed JFK(4)?");
					return -1;
				}
			}
			if(previousTracker != null) {
				if(Arrays.equals(outgoingKey, previousTracker.outgoingKey)
						&& Arrays.equals(incommingKey, previousTracker.incommingKey)) {
					Logger.error(this, "completedHandshake() with identical key to previous, maybe replayed JFK(4)?");
					return -1;
				}
			}
			if(unverifiedTracker != null) {
				if(Arrays.equals(outgoingKey, unverifiedTracker.outgoingKey)
						&& Arrays.equals(incommingKey, unverifiedTracker.incommingKey)) {
					Logger.error(this, "completedHandshake() with identical key to unverified, maybe replayed JFK(4)?");
					return -1;
				}
			}
			handshakeCount = 0;
			bogusNoderef = false;
			// Don't reset the uptime if we rekey
			if(!isConnected) {
				connectedTime = now;
				countSelectionsSinceConnected = 0;
				sentInitialMessages = false;
			} else
				wasARekey = true;
			isConnected = true;
			disableRouting = disableRoutingHasBeenSetLocally || disableRoutingHasBeenSetRemotely;
			isRoutable = routable;
			unroutableNewerVersion = newer;
			unroutableOlderVersion = older;
			boolean notReusingTracker = false;
			bootIDChanged = (thisBootID != this.bootID);
			if(myLastSuccessfulBootID != this.myBootID) {
				// If our own boot ID changed, because we forcibly disconnected, 
				// we need to use a new tracker. This is equivalent to us having restarted,
				// from the point of view of the other side, but since we haven't we need
				// to track it here.
				bootIDChanged = true;
				myLastSuccessfulBootID = myBootID;
			}
			if(bootIDChanged && wasARekey) {
				// This can happen if the other side thought we disconnected but we didn't think they did.
				Logger.normal(this, "Changed boot ID while rekeying! from " + bootID + " to " + thisBootID + " for " + getPeer());
				wasARekey = false;
				connectedTime = now;
				countSelectionsSinceConnected = 0;
				sentInitialMessages = false;
			} else if(bootIDChanged && logMINOR)
				Logger.minor(this, "Changed boot ID from " + bootID + " to " + thisBootID + " for " + getPeer());
			this.bootID = thisBootID;
			int firstPacketNumber = (negType >= 5 ? 0 : node.random.nextInt(100 * 1000));
			if(currentTracker != null && currentTracker.packets.trackerID == trackerID && !currentTracker.packets.isDeprecated()) {
				if(isJFK4 && !jfk4SameAsOld)
					Logger.error(this, "In JFK(4), found tracker ID "+trackerID+" but other side says is new! for "+this);
				packets = currentTracker.packets;
				if(logMINOR) Logger.minor(this, "Re-using packet tracker ID "+trackerID+" on "+this+" from current "+currentTracker);
			} else if(previousTracker != null && previousTracker.packets.trackerID == trackerID && !previousTracker.packets.isDeprecated()) {
				if(isJFK4 && !jfk4SameAsOld)
					Logger.error(this, "In JFK(4), found tracker ID "+trackerID+" but other side says is new! for "+this);
				packets = previousTracker.packets;
				if(logMINOR) Logger.minor(this, "Re-using packet tracker ID "+trackerID+" on "+this+" from prev "+previousTracker);
			} else if(isJFK4 && jfk4SameAsOld) {
				isConnected = false;
				Logger.error(this, "Can't reuse old tracker ID "+trackerID+" as instructed - disconnecting");
				return -1;
			} else if(trackerID == -1) {
				// Create a new tracker unconditionally
				packets = new PacketTracker(this, firstPacketNumber);
				if(negType >= 5) {
					if(previousTracker != null && previousTracker.packets.wasUsed()) {
						oldPrev = previousTracker;
						previousTracker = null;
						Logger.error(this, "Moving from old packet format to new packet format, previous tracker had packets in progress.");
					}
					if(currentTracker != null && currentTracker.packets.wasUsed()) {
						oldCur = currentTracker;
						currentTracker = null;
						Logger.error(this, "Moving from old packet format to new packet format, current tracker had packets in progress.");
					}
				} else {
					notReusingTracker = true;
				}
				if(logMINOR) Logger.minor(this, "Creating new PacketTracker as instructed for "+this);
			} else {
				if(isJFK4 && negType >= 4 && trackerID < 0)
					Logger.error(this, "JFK(4) packet with neg type "+negType+" has negative tracker ID: "+trackerID);

				notReusingTracker = true;
				if(isJFK4/* && !jfk4SameAsOld implied */ && trackerID >= 0) {
					packets = new PacketTracker(this, firstPacketNumber, trackerID);
				} else
					packets = new PacketTracker(this, firstPacketNumber);
				if(logMINOR) Logger.minor(this, "Creating new tracker (last resort) on "+this);
			}
			if(bootIDChanged || notReusingTracker) {
				if((!bootIDChanged) && notReusingTracker && !(currentTracker == null && previousTracker == null))
					// FIXME is this a real problem? Clearly the other side has changed trackers for some reason...
					// Normally that shouldn't happen except when a connection times out ... it is probably possible
					// for that to timeout on one side and not the other ...
					Logger.error(this, "Not reusing tracker, so wiping old trackers for "+this);
				oldPrev = previousTracker;
				oldCur = currentTracker;
				previousTracker = null;
				currentTracker = null;
			}
			if(bootIDChanged) {
				// Messages do not persist across restarts.
				// Generally they would be incomprehensible, anything that isn't should be sent as
				// connection initial messages by maybeOnConnect().
				messagesTellDisconnected = grabQueuedMessageItems();
				this.offeredMainJarVersion = 0;
				oldPacketFormat = packetFormat;
				packetFormat = null;
			} else {
				// else it's a rekey
			}
			newTracker = new SessionKey(this, packets, outgoingCipher, outgoingKey, incommingCipher, incommingKey, ivCipher, ivNonce, hmacKey, new NewPacketFormatKeyContext(ourInitialSeqNum, theirInitialSeqNum));
			if(logMINOR) Logger.minor(this, "New key tracker in completedHandshake: "+newTracker+" for "+packets+" for "+shortToString()+" neg type "+negType);
			if(unverified) {
				if(unverifiedTracker != null) {
					// Keep the old unverified tracker if possible.
					if(previousTracker == null)
						previousTracker = unverifiedTracker;
				}
				unverifiedTracker = newTracker;
				if(currentTracker == null || currentTracker.packets.isDeprecated())
					isConnected = false;
			} else {
				oldPrev = previousTracker;
				previousTracker = currentTracker;
				currentTracker = newTracker;
				// Keep the old unverified tracker.
				// In case of a race condition (two setups between A and B complete at the same time),
				// we might want to keep the unverified tracker rather than the previous tracker.
				neverConnected = false;
				maybeClearPeerAddedTimeOnConnect();
				maybeSwapTrackers();
				prev = previousTracker;
			}
			ctx = null;
			isRekeying = false;
			timeLastRekeyed = now - (unverified ? 0 : FNPPacketMangler.MAX_SESSION_KEY_REKEYING_DELAY / 2);
			totalBytesExchangedWithCurrentTracker = 0;
			// This has happened in the past, and caused problems, check for it.
			if(currentTracker != null && previousTracker != null &&
					Arrays.equals(currentTracker.outgoingKey, previousTracker.outgoingKey) &&
					Arrays.equals(currentTracker.incommingKey, previousTracker.incommingKey))
				Logger.error(this, "currentTracker key equals previousTracker key: cur "+currentTracker+" prev "+previousTracker);
			if(previousTracker != null && unverifiedTracker != null &&
					Arrays.equals(previousTracker.outgoingKey, unverifiedTracker.outgoingKey) &&
					Arrays.equals(previousTracker.incommingKey, unverifiedTracker.incommingKey))
				Logger.error(this, "previousTracker key equals unverifiedTracker key: prev "+previousTracker+" unv "+unverifiedTracker);
			timeLastSentPacket = now;
			if(packetFormat == null) {
				if(negType < 5) {
					packetFormat = new FNPWrapper(this);
				} else {
					packetFormat = new NewPacketFormat(this, ourInitialMsgID, theirInitialMsgID);
				}
			}
			// Completed setup counts as received data packet, for purposes of avoiding spurious disconnections.
			timeLastReceivedPacket = now;
			timeLastReceivedDataPacket = now;
			timeLastReceivedAck = now;
		}
		if(messagesTellDisconnected != null) {
			for(int i=0;i<messagesTellDisconnected.length;i++) {
				messagesTellDisconnected[i].onDisconnect();
			}
		}

		if(bootIDChanged) {
			node.lm.lostOrRestartedNode(this);
			node.usm.onRestart(this);
		}
		if(oldPrev != null && oldPrev.packets != newTracker.packets)
			oldPrev.packets.completelyDeprecated(newTracker);
		if(oldPrev != null) oldPrev.disconnected(true);
		if(oldCur != null && oldCur.packets != newTracker.packets)
			oldCur.packets.completelyDeprecated(newTracker);
		if(oldCur != null) oldCur.disconnected(true);
		if(prev != null && prev.packets != newTracker.packets)
			prev.packets.deprecated();
		if(oldPacketFormat != null) {
			List<MessageItem> tellDisconnect = oldPacketFormat.onDisconnect();
			if(tellDisconnect != null)
				for(MessageItem item : tellDisconnect) {
					item.onDisconnect();
				}
		}
		PacketThrottle throttle;
		synchronized(this) {
			throttle = _lastThrottle;
		}
		if(throttle != null) throttle.maybeDisconnected();
		Logger.normal(this, "Completed handshake with " + this + " on " + replyTo + " - current: " + currentTracker +
			" old: " + previousTracker + " unverified: " + unverifiedTracker + " bootID: " + thisBootID + (bootIDChanged ? "(changed) " : "") + " for " + shortToString());

		setPeerNodeStatus(now);

		if(newer || older || !isConnected())
			node.peers.disconnected(this);
		else if(!wasARekey) {
			node.peers.addConnectedPeer(this);
			maybeOnConnect();
		}

		return packets.trackerID;
	}

	protected abstract void maybeClearPeerAddedTimeOnConnect();

	/**
	 * Resolve race conditions where two connection setups between two peers complete simultaneously.
	 * Swap prev and current if:
	 * - There is a very short period between their respective creations.
	 * - Current's hashcode (including the key, the word "test", and the xor of the boot IDs) is
	 * greater than previous's.
	 */
	private synchronized void maybeSwapTrackers() {
		if(currentTracker == null || previousTracker == null) return;
		if(currentTracker.packets == previousTracker.packets) return;
		long delta = Math.abs(currentTracker.packets.createdTime - previousTracker.packets.createdTime);
		if(previousTracker != null && (!previousTracker.packets.isDeprecated()) &&
				delta < CHECK_FOR_SWAPPED_TRACKERS_INTERVAL) {
			// Swap prev and current iff H(new key) > H(old key).
			// To deal with race conditions (node A gets 1 current 2 prev, node B gets 2 current 1 prev; when we rekey we lose data and cause problems).

			// FIXME since this is a key dependancy, it needs to be looked at.
			// However, an attacker cannot get this far without knowing the privkey, so it's unlikely to be an issue.

			MessageDigest md = SHA256.getMessageDigest();
			md.update(currentTracker.outgoingKey);
			md.update(currentTracker.incommingKey);
			md.update(TEST_AS_BYTES);
			md.update(Fields.longToBytes(bootID ^ node.bootID));
			int curHash = Fields.hashCode(md.digest());
			md.reset();

			md.update(previousTracker.outgoingKey);
			md.update(previousTracker.incommingKey);
			md.update(TEST_AS_BYTES);
			md.update(Fields.longToBytes(bootID ^ node.bootID));
			int prevHash = Fields.hashCode(md.digest());
			SHA256.returnMessageDigest(md);

			if(prevHash < curHash) {
				// Swap over
				SessionKey temp = previousTracker;
				previousTracker = currentTracker;
				currentTracker = temp;
				if(logMINOR) Logger.minor(this, "Swapped SessionKey's on "+this+" cur "+currentTracker+" prev "+previousTracker+" delta "+delta+" cur.deprecated="+currentTracker.packets.isDeprecated()+" prev.deprecated="+previousTracker.packets.isDeprecated());
			} else {
				if(logMINOR) Logger.minor(this, "Not swapping SessionKey's on "+this+" cur "+currentTracker+" prev "+previousTracker+" delta "+delta+" cur.deprecated="+currentTracker.packets.isDeprecated()+" prev.deprecated="+previousTracker.packets.isDeprecated());
			}
		} else {
			if (logMINOR)
				Logger.minor(this, "Not swapping SessionKey's: previousTracker = " + previousTracker.toString()
				        + (previousTracker.packets.isDeprecated() ? " (deprecated)" : "") + " time delta = " + delta);
		}
	}

	public long getBootID() {
		return bootID;
	}
	private final Object arkFetcherSync = new Object();

	void startARKFetcher() {
		// FIXME any way to reduce locking here?
		if(!node.enableARKs) return;
		synchronized(arkFetcherSync) {
			if(myARK == null) {
				Logger.minor(this, "No ARK for " + this + " !!!!");
				return;
			}
			if(arkFetcher == null) {
				Logger.minor(this, "Starting ARK fetcher for " + this + " : " + myARK);
				arkFetcher = node.clientCore.uskManager.subscribeContent(myARK, this, true, node.arkFetcherContext, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, node.nonPersistentClientRT);
			}
		}
	}

	protected void stopARKFetcher() {
		if(!node.enableARKs) return;
		Logger.minor(this, "Stopping ARK fetcher for " + this + " : " + myARK);
		// FIXME any way to reduce locking here?
		synchronized(arkFetcherSync) {
			if(arkFetcher == null)
				return;
			node.clientCore.uskManager.unsubscribeContent(myARK, this.arkFetcher, true);
			arkFetcher = null;
		}
	}


	// Both at IMMEDIATE_SPLITFILE_PRIORITY_CLASS because we want to compete with FMS, not
	// wipe it out!

	public short getPollingPriorityNormal() {
		return RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
	}

	public short getPollingPriorityProgress() {
		return RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
	}

	boolean sentInitialMessages;

	void maybeSendInitialMessages() {
		synchronized(this) {
			if(sentInitialMessages)
				return;
			if(currentTracker != null && !currentTracker.packets.isDeprecated()) // FIXME is that possible?
				sentInitialMessages = true;
			else
				return;
		}

		sendInitialMessages();
	}

	/**
	* Send any high level messages that need to be sent on connect.
	*/
	protected void sendInitialMessages() {
		loadSender(true).setSendASAP();
		loadSender(false).setSendASAP();
		Message locMsg = DMT.createFNPLocChangeNotificationNew(node.lm.getLocation(), node.peers.getPeerLocationDoubles(true));
		Message ipMsg = DMT.createFNPDetectedIPAddress(detectedPeer);
		Message timeMsg = DMT.createFNPTime(System.currentTimeMillis());
		Message packetsMsg = createSentPacketsMessage();
		Message dRoutingMsg = DMT.createRoutingStatus(!disableRoutingHasBeenSetLocally);
		Message uptimeMsg = DMT.createFNPUptime((byte)(int)(100*node.uptime.getUptime()));

		try {
			if(isRealConnection())
				sendAsync(locMsg, null, node.nodeStats.initialMessagesCtr);
			sendAsync(ipMsg, null, node.nodeStats.initialMessagesCtr);
			sendAsync(timeMsg, null, node.nodeStats.initialMessagesCtr);
			sendAsync(packetsMsg, null, node.nodeStats.initialMessagesCtr);
			sendAsync(dRoutingMsg, null, node.nodeStats.initialMessagesCtr);
			sendAsync(uptimeMsg, null, node.nodeStats.initialMessagesCtr);
		} catch(NotConnectedException e) {
			Logger.error(this, "Completed handshake with " + getPeer() + " but disconnected (" + isConnected + ':' + currentTracker + "!!!: " + e, e);
		}

		if(isRealConnection())
			node.nodeUpdater.maybeSendUOMAnnounce(this);
		sendConnectedDiffNoderef();
	}

	private Message createSentPacketsMessage() {
		long[][] sent = getSentPacketTimesHashes();
		long[] times = sent[0];
		long[] hashes = sent[1];
		long now = System.currentTimeMillis();
		long horizon = now - Integer.MAX_VALUE;
		int skip = 0;
		for(int i = 0; i < times.length; i++) {
			long time = times[i];
			if(time < horizon)
				skip++;
			else
				break;
		}
		int[] timeDeltas = new int[times.length - skip];
		for(int i = skip; i < times.length; i++)
			timeDeltas[i] = (int) (now - times[i]);
		if(skip != 0) {
			// Unlikely code path, only happens with very long uptime.
			// Trim hashes too.
			long[] newHashes = new long[hashes.length - skip];
			System.arraycopy(hashes, skip, newHashes, 0, hashes.length - skip);
		}
		return DMT.createFNPSentPackets(timeDeltas, hashes, now);
	}

	private void sendIPAddressMessage() {
		Message ipMsg = DMT.createFNPDetectedIPAddress(detectedPeer);
		try {
			sendAsync(ipMsg, null, node.nodeStats.changedIPCtr);
		} catch(NotConnectedException e) {
			Logger.normal(this, "Sending IP change message to " + this + " but disconnected: " + e, e);
		}
	}

	/**
	* Called when a packet is successfully decrypted on a given
	* SessionKey for this node. Will promote the unverifiedTracker
	* if necessary.
	*/
	public void verified(SessionKey tracker) {
		long now = System.currentTimeMillis();
		SessionKey completelyDeprecatedTracker;
		synchronized(this) {
			if(tracker == unverifiedTracker && !tracker.packets.isDeprecated()) {
				if(logMINOR)
					Logger.minor(this, "Promoting unverified tracker " + tracker + " for " + getPeer());
				completelyDeprecatedTracker = previousTracker;
				previousTracker = currentTracker;
				currentTracker = unverifiedTracker;
				unverifiedTracker = null;
				isConnected = true;
				neverConnected = false;
				maybeClearPeerAddedTimeOnConnect();
				ctx = null;
				maybeSwapTrackers();
				if(previousTracker != null && previousTracker.packets != currentTracker.packets)
					previousTracker.packets.deprecated();
			} else
				return;
		}
		maybeSendInitialMessages();
		setPeerNodeStatus(now);
		node.peers.addConnectedPeer(this);
		maybeOnConnect();
		if(completelyDeprecatedTracker != null) {
			if(completelyDeprecatedTracker.packets != tracker.packets)
				completelyDeprecatedTracker.packets.completelyDeprecated(tracker);
			completelyDeprecatedTracker.disconnected(true);
		}
	}

	private synchronized boolean invalidVersion() {
		return bogusNoderef || forwardInvalidVersion() || reverseInvalidVersion();
	}

	private synchronized boolean forwardInvalidVersion() {
		return !Version.checkGoodVersion(version);
	}

	private synchronized boolean reverseInvalidVersion() {
		if(ignoreLastGoodVersion()) return false;
		return !Version.checkArbitraryGoodVersion(Version.getVersionString(), lastGoodVersion);
	}

	/**
	 * The same as isUnroutableOlderVersion, but not synchronized.
	 */
	public boolean publicInvalidVersion() {
		return unroutableOlderVersion;
	}

	/**
	 * The same as inUnroutableNewerVersion.
	 */
	public synchronized boolean publicReverseInvalidVersion() {
		return unroutableNewerVersion;
	}

	public synchronized boolean dontRoute() {
		return disableRouting;
	}

	/**
	* Process a differential node reference
	* The identity must not change, or we throw.
	*/
	public void processDiffNoderef(SimpleFieldSet fs) throws FSParseException {
		processNewNoderef(fs, false, true);
	}

	/**
	* Process a new nodereference, in compressed form.
	* The identity must not change, or we throw.
	*/
	private void processNewNoderef(byte[] data, int offset, int length) throws FSParseException {
		SimpleFieldSet fs = compressedNoderefToFieldSet(data, offset, length);
		processNewNoderef(fs, false, false);
	}

	static SimpleFieldSet compressedNoderefToFieldSet(byte[] data, int offset, int length) throws FSParseException {
		if(length <= 5)
			throw new FSParseException("Too short");
		// Lookup table for groups.
		DSAGroup group = null;
		int firstByte = data[offset];
		offset++;
		length--;
		if((firstByte & 0x2) == 2) {
			int groupIndex = (data[offset] & 0xff);
			offset++;
			length--;
			group = Global.getGroup(groupIndex);
			if(group == null) throw new FSParseException("Unknown group number "+groupIndex);
			if(logMINOR)
				Logger.minor(PeerNode.class, "DSAGroup set to "+group.fingerprintToString()+ " using the group-index "+groupIndex);
		}
		// Is it compressed?
		if((firstByte & 1) == 1) {
			try {
				// Gzipped
				Inflater i = new Inflater();
				i.setInput(data, offset, length);
				// We shouldn't ever need a 4096 bytes long ref!
				byte[] output = new byte[4096];
				length = i.inflate(output, 0, output.length);
				// Finished
				data = output;
				offset = 0;
				if(logMINOR)
					Logger.minor(PeerNode.class, "We have decompressed a "+length+" bytes big reference.");
			} catch(DataFormatException e) {
				throw new FSParseException("Invalid compressed data");
			}
		}
		if(logMINOR)
			Logger.minor(PeerNode.class, "Reference: " + HexUtil.bytesToHex(data, offset, length) + '(' + length + ')');

		// Now decode it
		ByteArrayInputStream bais = new ByteArrayInputStream(data, offset, length);
		InputStreamReader isr;
		try {
			isr = new InputStreamReader(bais, "UTF-8");
		} catch(UnsupportedEncodingException e1) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e1, e1);
		}
		BufferedReader br = new BufferedReader(isr);
		try {
			SimpleFieldSet fs = new SimpleFieldSet(br, false, true);
			if(group != null) {
				SimpleFieldSet sfs = new SimpleFieldSet(true);
				sfs.put("dsaGroup", group.asFieldSet());
				fs.putAllOverwrite(sfs);
			}
			return fs;
		} catch(IOException e) {
			throw (FSParseException)new FSParseException("Impossible: " + e).initCause(e);
		}
	}

	/**
	* Process a new nodereference, as a SimpleFieldSet.
	*/
	private void processNewNoderef(SimpleFieldSet fs, boolean forARK, boolean forDiffNodeRef) throws FSParseException {
		if(logMINOR)
			Logger.minor(this, "Parsing: \n" + fs);
		boolean changedAnything = innerProcessNewNoderef(fs, forARK, forDiffNodeRef) || forARK;
		if(changedAnything && !isSeed())
			node.peers.writePeers();
	}

	/**
	* The synchronized part of processNewNoderef
	* @throws FSParseException
	*/
	protected synchronized boolean innerProcessNewNoderef(SimpleFieldSet fs, boolean forARK, boolean forDiffNodeRef) throws FSParseException {
		// Anything may be omitted for a differential node reference
		boolean changedAnything = false;
		if(!forDiffNodeRef && (false != Fields.stringToBool(fs.get("testnet"), false))) {
			String err = "Preventing connection to node " + detectedPeer +" - testnet is enabled!";
			Logger.error(this, err);
			throw new FSParseException(err);
		}
		String newVersion = fs.get("version");
		if(newVersion == null) {
			// Version may be ommitted for an ARK.
			if(!forARK && !forDiffNodeRef)
				throw new FSParseException("No version");
		} else {
			if(!newVersion.equals(version))
				changedAnything = true;
			version = newVersion;
			if(version != null) {
				try {
					simpleVersion = Version.getArbitraryBuildNumber(version);
				} catch (VersionParseException e) {
					Logger.error(this, "Bad version: " + version + " : " + e, e);
				}
			}
			Version.seenVersion(newVersion);
		}
		String newLastGoodVersion = fs.get("lastGoodVersion");
		if(newLastGoodVersion != null) {
			// Can be null if anon auth or if forDiffNodeRef.
			lastGoodVersion = newLastGoodVersion;
		}

		updateVersionRoutablity();

		String locationString = fs.get("location");
		if(locationString != null) {
			double newLoc = Location.getLocation(locationString);
			if (newLoc == -1) {
				if(logMINOR)
					Logger.minor(this, "Invalid or null location, waiting for FNPLocChangeNotification: locationString=" + locationString);
			} else {
				if(!Location.equals(newLoc, currentLocation)) {
					changedAnything = true;
					currentLocation = newLoc;
					locSetTime = System.currentTimeMillis();
				}
			}
		}
		try {
			String physical[] = fs.getAll("physical.udp");
			if(physical != null) {
				Vector<Peer> oldNominalPeer = nominalPeer;

				if(nominalPeer == null)
					nominalPeer = new Vector<Peer>();
				nominalPeer.removeAllElements();

				Peer[] oldPeers = nominalPeer.toArray(new Peer[nominalPeer.size()]);

				for(int i = 0; i < physical.length; i++) {
					Peer p;
					try {
						p = new Peer(physical[i], true, true);
					} catch(HostnameSyntaxException e) {
						Logger.error(this, "Invalid hostname or IP Address syntax error while parsing new peer reference: " + physical[i]);
						continue;
					} catch (PeerParseException e) {
						Logger.error(this, "Invalid hostname or IP Address syntax error while parsing new peer reference: " + physical[i]);
						continue;
					} catch (UnknownHostException e) {
						// Should be impossible???
						Logger.error(this, "Invalid hostname or IP Address syntax error while parsing new peer reference: " + physical[i]);
						continue;
					}
					if(!nominalPeer.contains(p)) {
						if(oldNominalPeer.contains(p)) {
							// Do nothing
							// .contains() will .equals() on each, and equals() will propagate the looked-up IP if necessary.
							// This is obviously O(n^2), but it doesn't matter, there will be very few peers.
						}
						nominalPeer.addElement(p);
					}
				}
				if(!Arrays.equals(oldPeers, nominalPeer.toArray(new Peer[nominalPeer.size()]))) {
					changedAnything = true;
					if(logMINOR) Logger.minor(this, "Got new physical.udp for "+this+" : "+Arrays.toString(nominalPeer.toArray()));
					lastAttemptedHandshakeIPUpdateTime = 0;
					// Clear nonces to prevent leak. Will kill any in-progress connect attempts, but that is okay because
					// either we got an ARK which changed our peers list, or we just connected.
					jfkNoncesSent.clear();
				}

			} else if(forARK) {
				// Connection setup doesn't include a physical.udp.
				// Differential noderefs only include it on the first one after connect.
				Logger.error(this, "ARK noderef has no physical.udp for "+this+" : forDiffNodeRef="+forDiffNodeRef+" forARK="+forARK);
			}
		} catch(Exception e1) {
			Logger.error(this, "Caught "+e1, e1);
			throw new FSParseException(e1);
		}

		if(logMINOR)
			Logger.minor(this, "Parsed successfully; changedAnything = " + changedAnything);

		int[] newNegTypes = fs.getIntArray("auth.negTypes");

		boolean refHadNegTypes = false;

		if(newNegTypes == null || newNegTypes.length == 0) {
			newNegTypes = new int[]{0};
		} else {
			refHadNegTypes = true;
		}
		if(!forDiffNodeRef || refHadNegTypes) {
			if(!Arrays.equals(negTypes, newNegTypes)) {
				changedAnything = true;
				negTypes = newNegTypes;
			}
		}

		if(parseARK(fs, false, forDiffNodeRef))
			changedAnything = true;
		return changedAnything;
	}

	/**
	* Send a payload-less packet on either key if necessary.
	* @throws PacketSequenceException If there is an error sending the packet
	* caused by a sequence inconsistency.
	*/
	public boolean sendAnyUrgentNotifications(boolean forceSendPrimary) {
		boolean sent = false;
		if(logMINOR)
			Logger.minor(this, "sendAnyUrgentNotifications");
		long now = System.currentTimeMillis();
		SessionKey cur,
		 prev;
		synchronized(this) {
			cur = currentTracker;
			prev = previousTracker;
		}
		SessionKey tracker = cur;
		if(tracker != null) {
			long t = tracker.packets.getNextUrgentTime();
			if(t < now || forceSendPrimary) {
				try {
					if(logMINOR) Logger.minor(this, "Sending urgent notifications for current tracker on "+shortToString());
					int size = outgoingMangler.processOutgoing(null, 0, 0, tracker, DMT.PRIORITY_NOW);
					node.nodeStats.reportNotificationOnlyPacketSent(size);
					sent = true;
				} catch(NotConnectedException e) {
				// Ignore
				} catch(KeyChangedException e) {
				// Ignore
				} catch(WouldBlockException e) {
					Logger.error(this, "Caught impossible: "+e, e);
				} catch(PacketSequenceException e) {
					Logger.error(this, "Caught impossible: "+e, e);
				}
			}
		}
		tracker = prev;
		if(tracker != null) {
			long t = tracker.packets.getNextUrgentTime();
			if(t < now)
				try {
					if(logMINOR) Logger.minor(this, "Sending urgent notifications for previous tracker on "+shortToString());
					int size = outgoingMangler.processOutgoing(null, 0, 0, tracker, DMT.PRIORITY_NOW);
					node.nodeStats.reportNotificationOnlyPacketSent(size);
					sent = true;
				} catch(NotConnectedException e) {
				// Ignore
				} catch(KeyChangedException e) {
				// Ignore
				} catch(WouldBlockException e) {
					Logger.error(this, "Caught impossible: "+e, e);
				} catch(PacketSequenceException e) {
					Logger.error(this, "Caught impossible: "+e, e);
				}
		}
		return sent;
	}

	void checkTrackerTimeout() {
		long now = System.currentTimeMillis();
		SessionKey prev = null;
		SessionKey cur = null;
		synchronized(this) {
			if(previousTracker == null) return;
			if(currentTracker == null) return;
			cur = currentTracker;
			prev = previousTracker;
		}
		if(prev.packets == cur.packets) return;
		long t = prev.packets.getNextUrgentTime();
		if(!(t > -1 && prev.packets.timeLastDecodedPacket() > 0 && (now - prev.packets.timeLastDecodedPacket()) > 60*1000 &&
				cur.packets.timeLastDecodedPacket() > 0 && (now - cur.packets.timeLastDecodedPacket() < 30*1000) &&
				(prev.packets.countAckRequests() > 0 || prev.packets.countResendRequests() > 0)))
			return;
		Logger.error(this, "No packets decoded on "+prev+" for 60 seconds, deprecating in favour of cur: "+cur);
		prev.packets.completelyDeprecated(cur);
	}

	/**
	 * Get a PeerNodeStatus for this node.
	 * @param noHeavy If true, avoid any expensive operations e.g. the message count hashtables.
	 */
	public abstract PeerNodeStatus getStatus(boolean noHeavy);

	public String getTMCIPeerInfo() {
		long now = System.currentTimeMillis();
		int idle = -1;
		synchronized(this) {
			idle = (int) ((now - timeLastReceivedPacket) / 1000);
		}
		if((getPeerNodeStatus() == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED) && (getPeerAddedTime() > 1))
			idle = (int) ((now - getPeerAddedTime()) / 1000);
		return String.valueOf(getPeer()) + '\t' + getIdentityString() + '\t' + getLocation() + '\t' + getPeerNodeStatusString() + '\t' + idle;
	}

	public String getFreevizOutput() {
		return getStatus(true).toString() + '|' + identityAsBase64String;
	}

	public synchronized String getVersion() {
		return version;
	}

	private synchronized String getLastGoodVersion() {
		return lastGoodVersion;
	}

	private int simpleVersion;

	public int getSimpleVersion() {
		return simpleVersion;
	}

	/**
	* Write the peer's noderef to disk
	*/
	public void write(Writer w) throws IOException {
		SimpleFieldSet fs = exportFieldSet();
		SimpleFieldSet meta = exportMetadataFieldSet();
		if(!meta.isEmpty())
			fs.put("metadata", meta);
		fs.writeTo(w);
	}

	/**
	 * (both metadata + normal fieldset but atomically)
	 */
	public synchronized SimpleFieldSet exportDiskFieldSet() {
		SimpleFieldSet fs = exportFieldSet();
		SimpleFieldSet meta = exportMetadataFieldSet();
		if(!meta.isEmpty())
			fs.put("metadata", meta);
		return fs;
	}

	/**
	* Export metadata about the node as a SimpleFieldSet
	*/
	public synchronized SimpleFieldSet exportMetadataFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		if(detectedPeer != null)
			fs.putSingle("detected.udp", detectedPeer.toStringPrefNumeric());
		if(lastReceivedPacketTime() > 0)
			fs.putSingle("timeLastReceivedPacket", Long.toString(timeLastReceivedPacket));
		if(lastReceivedAckTime() > 0)
			fs.putSingle("timeLastReceivedAck", Long.toString(timeLastReceivedAck));
		if(timeLastConnected() > 0)
			fs.putSingle("timeLastConnected", Long.toString(timeLastConnected));
		if(timeLastRoutable() > 0)
			fs.putSingle("timeLastRoutable", Long.toString(timeLastRoutable));
		if(getPeerAddedTime() > 0 && shouldExportPeerAddedTime())
			fs.putSingle("peerAddedTime", Long.toString(peerAddedTime));
		if(neverConnected)
			fs.putSingle("neverConnected", "true");
		if(hadRoutableConnectionCount > 0)
			fs.putSingle("hadRoutableConnectionCount", Long.toString(hadRoutableConnectionCount));
		if(routableConnectionCheckCount > 0)
			fs.putSingle("routableConnectionCheckCount", Long.toString(routableConnectionCheckCount));
		if(currentPeersLocation != null)
			fs.put("peersLocation", currentPeersLocation);
		return fs;
	}

	// Opennet peers don't persist or export the peer added time.
	protected abstract boolean shouldExportPeerAddedTime();

	/**
	* Export volatile data about the node as a SimpleFieldSet
	*/
	public SimpleFieldSet exportVolatileFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		long now = System.currentTimeMillis();
		synchronized(this) {
			fs.putSingle("averagePingTime", Double.toString(averagePingTime()));
			long idle = now - lastReceivedPacketTime();
			if(idle > (60 * 1000) && -1 != lastReceivedPacketTime())  // 1 minute

				fs.putSingle("idle", Long.toString(idle));
			if(peerAddedTime > 1)
				fs.putSingle("peerAddedTime", Long.toString(peerAddedTime));
			fs.putSingle("lastRoutingBackoffReasonRT", lastRoutingBackoffReasonRT);
			fs.putSingle("lastRoutingBackoffReasonBulk", lastRoutingBackoffReasonBulk);
			fs.putSingle("routingBackoffPercent", Double.toString(backedOffPercent.currentValue() * 100));
			fs.putSingle("routingBackoffRT", Long.toString((Math.max(Math.max(routingBackedOffUntilRT, transferBackedOffUntilRT) - now, 0))));
			fs.putSingle("routingBackoffBulk", Long.toString((Math.max(Math.max(routingBackedOffUntilBulk, transferBackedOffUntilBulk) - now, 0))));
			fs.putSingle("routingBackoffLengthRT", Integer.toString(routingBackoffLengthRT));
			fs.putSingle("routingBackoffLengthBulk", Integer.toString(routingBackoffLengthBulk));
			fs.putSingle("overloadProbability", Double.toString(getPRejected() * 100));
			fs.putSingle("percentTimeRoutableConnection", Double.toString(getPercentTimeRoutableConnection() * 100));
		}
		fs.putSingle("status", getPeerNodeStatusString());
		return fs;
	}

	/**
	* Export the peer's noderef as a SimpleFieldSet
	*/
	public synchronized SimpleFieldSet exportFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		if(getLastGoodVersion() != null)
			fs.putSingle("lastGoodVersion", lastGoodVersion);
		for(int i = 0; i < nominalPeer.size(); i++)
			fs.putAppend("physical.udp", nominalPeer.get(i).toString());
		fs.put("auth.negTypes", negTypes);
		fs.putSingle("identity", getIdentityString());
		fs.putSingle("location", Double.toString(currentLocation));
		fs.putSingle("testnet", Boolean.toString(testnetEnabled));
		fs.putSingle("version", version);
		if(peerCryptoGroup != null)
			fs.put("dsaGroup", peerCryptoGroup.asFieldSet());
		if(peerPubKey != null)
			fs.put("dsaPubKey", peerPubKey.asFieldSet());
		if(myARK != null) {
			// Decrement it because we keep the number we would like to fetch, not the last one fetched.
			fs.putSingle("ark.number", Long.toString(myARK.suggestedEdition - 1));
			fs.putSingle("ark.pubURI", myARK.getBaseSSK().toString(false, false));
		}
		fs.put("opennet", isOpennet());
		fs.put("seed", isSeed());
		fs.put("totalInput", (getTotalInputSinceStartup()+getTotalInputBytes()));
		fs.put("totalOutput", (getTotalOutputSinceStartup()+getTotalOutputBytes()));
		return fs;
	}

	public abstract boolean isDarknet();

	public abstract boolean isOpennet();

	public abstract boolean isSeed();

	/**
	* @return The time at which we last connected (or reconnected).
	*/
	public synchronized long timeLastConnectionCompleted() {
		return connectedTime;
	}

	/**
	* Requeue ResendPacketItem[]s if they are not sent.
	* @param resendItems
	*/
	public void requeueResendItems(Vector<ResendPacketItem> resendItems) {
		SessionKey cur,
		 prev,
		 unv;
		synchronized(this) {
			cur = currentTracker;
			prev = previousTracker;
			unv = unverifiedTracker;
		}
		for(ResendPacketItem item : resendItems) {
			if(item.pn != this)
				throw new IllegalArgumentException("item.pn != this!");
			SessionKey kt = cur;
			if((kt != null) && (item.kt == kt.packets)) {
				kt.packets.resendPacket(item.packetNumber);
				continue;
			}
			kt = prev;
			if((kt != null) && (item.kt == kt.packets)) {
				kt.packets.resendPacket(item.packetNumber);
				continue;
			}
			kt = unv;
			if((kt != null) && (item.kt == kt.packets)) {
				kt.packets.resendPacket(item.packetNumber);
				continue;
			}
			// Doesn't match any of these, need to resend the data
			kt = cur == null ? unv : cur;
			if(kt == null) {
				Logger.error(this, "No tracker to resend packet " + item.packetNumber + " on");
				continue;
			}
			MessageItem mi = new MessageItem(item.buf, item.callbacks, true, resendByteCounter, item.priority, false, false);
			requeueMessageItems(new MessageItem[]{mi}, 0, 1, true);
		}
	}

	@Override
	public boolean equals(Object o) {
		if(o == this)
			return true;
		if(o instanceof PeerNode) {
			PeerNode pn = (PeerNode) o;
			return Arrays.equals(pn.identity, identity);
		} else
			return false;
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	public boolean isRoutingBackedOff(int ignoreBackoffUnder, boolean realTime) {
		long now = System.currentTimeMillis();
		double pingTime;
		synchronized(this) {
			long routingBackedOffUntil = realTime ? routingBackedOffUntilRT : routingBackedOffUntilBulk;
			if(now < routingBackedOffUntil) {
				if(routingBackedOffUntil - now >= ignoreBackoffUnder) return true;
			}
			long transferBackedOffUntil = realTime ? transferBackedOffUntilRT : transferBackedOffUntilBulk;
			if(now < transferBackedOffUntil) {
				if(transferBackedOffUntil - now >= ignoreBackoffUnder) return true;
			}
			pingTime = averagePingTime();
		}
		if(pingTime > maxPeerPingTime()) return true;
		return false;
	}
	
	public boolean isRoutingBackedOff(boolean realTime) {
		long now = System.currentTimeMillis();
		double pingTime;
		synchronized(this) {
			long routingBackedOffUntil = realTime ? routingBackedOffUntilRT : routingBackedOffUntilBulk;
			long transferBackedOffUntil = realTime ? transferBackedOffUntilRT : transferBackedOffUntilBulk;
			if(now < routingBackedOffUntil || now < transferBackedOffUntil) return true;
			pingTime = averagePingTime();
		}
		if(pingTime > maxPeerPingTime()) return true;
		return false;
	}
	
	public boolean isRoutingBackedOffEither() {
		long now = System.currentTimeMillis();
		double pingTime;
		synchronized(this) {
			long routingBackedOffUntil = Math.max(routingBackedOffUntilRT, routingBackedOffUntilBulk);
			long transferBackedOffUntil = Math.max(transferBackedOffUntilRT, transferBackedOffUntilBulk);
			if(now < routingBackedOffUntil || now < transferBackedOffUntil) return true;
			pingTime = averagePingTime();
		}
		if(pingTime > maxPeerPingTime()) return true;
		return false;
	}
	
	long routingBackedOffUntilRT = -1;
	long routingBackedOffUntilBulk = -1;
	/** Initial nominal routing backoff length */
	static final int INITIAL_ROUTING_BACKOFF_LENGTH = 1000;  // 1 second
	/** How much to multiply by during fast routing backoff */

	static final int BACKOFF_MULTIPLIER = 2;
	/** Maximum upper limit to routing backoff slow or fast */
	static final int MAX_ROUTING_BACKOFF_LENGTH = 3 * 60 * 60 * 1000;  // 3 hours
	/** Current nominal routing backoff length */

	// Transfer Backoff

	long transferBackedOffUntilRT = -1;
	long transferBackedOffUntilBulk = -1;
	static final int INITIAL_TRANSFER_BACKOFF_LENGTH = 30*1000; // 60 seconds, but it starts at twice this.
	static final int TRANSFER_BACKOFF_MULTIPLIER = 2;
	static final int MAX_TRANSFER_BACKOFF_LENGTH = 3 * 60 * 60 * 1000; // 3 hours

	int transferBackoffLengthRT = INITIAL_TRANSFER_BACKOFF_LENGTH;
	int transferBackoffLengthBulk = INITIAL_TRANSFER_BACKOFF_LENGTH;

	int routingBackoffLengthRT = INITIAL_ROUTING_BACKOFF_LENGTH;
	int routingBackoffLengthBulk = INITIAL_ROUTING_BACKOFF_LENGTH;
	/** Last backoff reason */
	String lastRoutingBackoffReasonRT;
	String lastRoutingBackoffReasonBulk;
	/** Previous backoff reason (used by setPeerNodeStatus)*/
	String previousRoutingBackoffReasonRT;
	String previousRoutingBackoffReasonBulk;
	/* percent of time this peer is backed off */
	public final RunningAverage backedOffPercent;
	public final RunningAverage backedOffPercentRT;
	public final RunningAverage backedOffPercentBulk;
	/* time of last sample */
	private long lastSampleTime = Long.MAX_VALUE;

	/**
	 * Track the percentage of time a peer spends backed off
	 */
	private void reportBackoffStatus(long now) {
		synchronized(this) {
			if(now > lastSampleTime) { // don't report twice in the same millisecond
				double report = 0.0;
				if(now > routingBackedOffUntilRT) { // not backed off
					if(lastSampleTime > routingBackedOffUntilRT) { // last sample after last backoff
						backedOffPercentRT.report(0.0);
						report = 0.0;
					}else {
						if(routingBackedOffUntilRT > 0) {
							report = (double) (routingBackedOffUntilRT - lastSampleTime) / (double) (now - lastSampleTime);
							backedOffPercentRT.report(report);
						}
					}
				} else {
					report = 0.0;
					backedOffPercentRT.report(1.0);
				}
				
				if(now > routingBackedOffUntilBulk) { // not backed off
					if(lastSampleTime > routingBackedOffUntilBulk) { // last sample after last backoff
						report = 0.0;
						backedOffPercentBulk.report(0.0);
					}else {
						if(routingBackedOffUntilBulk > 0) {
							double myReport = (double) (routingBackedOffUntilBulk - lastSampleTime) / (double) (now - lastSampleTime);
							backedOffPercentBulk.report(myReport);
							if(report > myReport) report = myReport;
						}
					}
				} else {
					backedOffPercentBulk.report(1.0);
				}
				backedOffPercent.report(report);
			}
			lastSampleTime = now;
		}
	}

	/**
	 * Got a local RejectedOverload.
	 * Back off this node for a while.
	 */
	public void localRejectedOverload(String reason, boolean realTime) {
		assert reason.indexOf(" ") == -1;
		pRejected.report(1.0);
		if(logMINOR)
			Logger.minor(this, "Local rejected overload (" + reason + ") on " + this + " : pRejected=" + pRejected.currentValue());
		long now = System.currentTimeMillis();
		Peer peer = getPeer();
		reportBackoffStatus(now);
		// We need it because of nested locking on getStatus()
		synchronized(this) {
			// Don't back off any further if we are already backed off
			long routingBackedOffUntil = realTime ? routingBackedOffUntilRT : routingBackedOffUntilBulk;
			int routingBackoffLength = realTime ? routingBackoffLengthRT : routingBackoffLengthBulk;
			if(now > routingBackedOffUntil) {
				routingBackoffLength = routingBackoffLength * BACKOFF_MULTIPLIER;
				if(routingBackoffLength > MAX_ROUTING_BACKOFF_LENGTH)
					routingBackoffLength = MAX_ROUTING_BACKOFF_LENGTH;
				int x = node.random.nextInt(routingBackoffLength);
				routingBackedOffUntil = now + x;
				node.nodeStats.reportRoutingBackoff(reason, x, realTime);
				String reasonWrapper = "";
				if(0 <= reason.length())
					reasonWrapper = " because of '" + reason + '\'';
				if(logMINOR)
					Logger.minor(this, "Backing off" + reasonWrapper + ": routingBackoffLength=" + routingBackoffLength + ", until " + x + "ms on " + peer);
				if(realTime) {
					routingBackedOffUntilRT = routingBackedOffUntil;
					routingBackoffLengthRT = routingBackoffLength;
				} else {
					routingBackedOffUntilBulk = routingBackedOffUntil;
					routingBackoffLengthBulk = routingBackoffLength;
				}
			} else {
				if(logMINOR)
					Logger.minor(this, "Ignoring localRejectedOverload: " + (routingBackedOffUntil - now) + "ms remaining on routing backoff on " + peer);
				return;
			}
			setLastBackoffReason(reason, realTime);
		}
		setPeerNodeStatus(now);
		if(realTime)
			outputLoadTrackerRealTime.failSlotWaiters(true);
		else
			outputLoadTrackerBulk.failSlotWaiters(true);
	}

	/**
	 * Didn't get RejectedOverload.
	 * Reset routing backoff.
	 */
	public void successNotOverload(boolean realTime) {
		pRejected.report(0.0);
		if(logMINOR)
			Logger.minor(this, "Success not overload on " + this + " : pRejected=" + pRejected.currentValue());
		Peer peer = getPeer();
		long now = System.currentTimeMillis();
		reportBackoffStatus(now);
		synchronized(this) {
			// Don't un-backoff if still backed off
			long until;
			if(now > (until = realTime ? routingBackedOffUntilRT : routingBackedOffUntilBulk)) {
				if(realTime)
					routingBackoffLengthRT = INITIAL_ROUTING_BACKOFF_LENGTH;
				else
					routingBackoffLengthBulk = INITIAL_ROUTING_BACKOFF_LENGTH;
				if(logMINOR)
					Logger.minor(this, "Resetting routing backoff on " + peer);
			} else {
				if(logMINOR)
					Logger.minor(this, "Ignoring successNotOverload: " + (until - now) + "ms remaining on routing backoff on " + peer);
				return;
			}
		}
		setPeerNodeStatus(now);
	}

	/**
	 * A transfer failed.
	 * Back off this node for a while.
	 */
	public void transferFailed(String reason, boolean realTime) {
		assert reason.indexOf(" ") == -1;
		pRejected.report(1.0);
		if(logMINOR)
			Logger.minor(this, "Transfer failed (" + reason + ") on " + this + " : pRejected=" + pRejected.currentValue());
		long now = System.currentTimeMillis();
		Peer peer = getPeer();
		reportBackoffStatus(now);
		// We need it because of nested locking on getStatus()
		synchronized(this) {
			// Don't back off any further if we are already backed off
			long transferBackedOffUntil = realTime ? transferBackedOffUntilRT : transferBackedOffUntilBulk;
			int transferBackoffLength = realTime ? transferBackoffLengthRT : transferBackoffLengthBulk;
			if(now > transferBackedOffUntil) {
				transferBackoffLength = transferBackoffLength * TRANSFER_BACKOFF_MULTIPLIER;
				if(transferBackoffLength > MAX_TRANSFER_BACKOFF_LENGTH)
					transferBackoffLength = MAX_TRANSFER_BACKOFF_LENGTH;
				int x = node.random.nextInt(transferBackoffLength);
				transferBackedOffUntil = now + x;
				node.nodeStats.reportTransferBackoff(reason, x, realTime);
				String reasonWrapper = "";
				if(0 <= reason.length())
					reasonWrapper = " because of '" + reason + '\'';
				if(logMINOR)
					Logger.minor(this, "Backing off (transfer)" + reasonWrapper + ": transferBackoffLength=" + transferBackoffLength + ", until " + x + "ms on " + peer);
				if(realTime) {
					transferBackedOffUntilRT = transferBackedOffUntil;
					transferBackoffLengthRT = transferBackoffLength;
				} else {
					transferBackedOffUntilBulk = transferBackedOffUntil;
					transferBackoffLengthBulk = transferBackoffLength;
				}
			} else {
				if(logMINOR)
					Logger.minor(this, "Ignoring transfer failure: " + (transferBackedOffUntil - now) + "ms remaining on transfer backoff on " + peer);
				return;
			}
			setLastBackoffReason(reason, realTime);
		}
		setPeerNodeStatus(now);
	}

	/**
	 * A transfer succeeded.
	 * Reset backoff.
	 */
	public void transferSuccess(boolean realTime) {
		pRejected.report(0.0);
		if(logMINOR)
			Logger.minor(this, "Transfer success on " + this + " : pRejected=" + pRejected.currentValue());
		Peer peer = getPeer();
		long now = System.currentTimeMillis();
		reportBackoffStatus(now);
		synchronized(this) {
			// Don't un-backoff if still backed off
			long until;
			if(now > (until = realTime ? transferBackedOffUntilRT : transferBackedOffUntilBulk)) {
				if(realTime)
					routingBackoffLengthRT = INITIAL_TRANSFER_BACKOFF_LENGTH;
				else
					routingBackoffLengthBulk = INITIAL_TRANSFER_BACKOFF_LENGTH;
				if(logMINOR)
					Logger.minor(this, "Resetting transfer backoff on " + peer);
			} else {
				if(logMINOR)
					Logger.minor(this, "Ignoring transfer success: " + (until - now) + "ms remaining on transfer backoff on " + peer);
				return;
			}
		}
		setPeerNodeStatus(now);
	}


	Object pingSync = new Object();
	// Relatively few as we only get one every 200ms*#nodes
	// We want to get reasonably early feedback if it's dropping all of them...

	final static int MAX_PINGS = 5;
	long pingNumber;
	private final RunningAverage pingAverage;

	/**
	 * @return The probability of a request sent to this peer being rejected (locally)
	 * due to overload, or timing out after being accepted.
	 */
	public double getPRejected() {
		return pRejected.currentValue();
	}

	public double averagePingTime() {
		return pingAverage.currentValue();
	}
	
	private boolean reportedRTT;
	private double SRTT = 1000;
	private double RTTVAR = 0;
	private double RTO = 1000;
	
	/** Calculated as per RFC 2988 */
	public synchronized double averagePingTimeCorrected() {
		return RTO; 
	}

	public void reportThrottledPacketSendTime(long timeDiff, boolean realTime) {
		// FIXME do we need this?
		if(logMINOR)
			Logger.minor(this, "Reporting throttled packet send time: " + timeDiff + " to " + getPeer()+" ("+(realTime?"realtime":"bulk")+")");
	}

	public void setRemoteDetectedPeer(Peer p) {
		this.remoteDetectedPeer = p;
	}

	public Peer getRemoteDetectedPeer() {
		return remoteDetectedPeer;
	}

	public synchronized int getRoutingBackoffLength(boolean realTime) {
		return realTime ? routingBackoffLengthRT : routingBackoffLengthBulk;
	}

	public synchronized long getRoutingBackedOffUntil(boolean realTime) {
		return Math.max(realTime ? routingBackedOffUntilRT : routingBackedOffUntilBulk, 
				realTime ? transferBackedOffUntilRT : transferBackedOffUntilBulk);
	}
	
	public synchronized long getRoutingBackedOffUntilMax() {
		return Math.max(
				Math.max(routingBackedOffUntilRT, routingBackedOffUntilBulk),
				Math.max(transferBackedOffUntilRT, transferBackedOffUntilBulk));
	}

	public synchronized String getLastBackoffReason(boolean realTime) {
		return realTime ? lastRoutingBackoffReasonRT : lastRoutingBackoffReasonBulk;
	}

	public synchronized String getPreviousBackoffReason(boolean realTime) {
		return realTime ? previousRoutingBackoffReasonRT : previousRoutingBackoffReasonBulk;
	}

	public synchronized void setLastBackoffReason(String s, boolean realTime) {
		if(realTime)
			lastRoutingBackoffReasonRT = s;
		else
			lastRoutingBackoffReasonBulk = s;
	}

	public void addToLocalNodeSentMessagesToStatistic(Message m) {
		String messageSpecName;
		Long count;

		messageSpecName = m.getSpec().getName();
		// Synchronize to make increments atomic.
		synchronized(localNodeSentMessageTypes) {
			count = localNodeSentMessageTypes.get(messageSpecName);
			if(count == null)
				count = 1L;
			else
				count = count.longValue() + 1;
			localNodeSentMessageTypes.put(messageSpecName, count);
		}
	}

	public void addToLocalNodeReceivedMessagesFromStatistic(Message m) {
		String messageSpecName;
		Long count;

		messageSpecName = m.getSpec().getName();
		// Synchronize to make increments atomic.
		synchronized(localNodeReceivedMessageTypes) {
			count = localNodeReceivedMessageTypes.get(messageSpecName);
			if(count == null)
				count = 1L;
			else
				count = count.longValue() + 1;
			localNodeReceivedMessageTypes.put(messageSpecName, count);
		}
	}

	public Hashtable<String,Long> getLocalNodeSentMessagesToStatistic() {
		// Must be synchronized *during the copy*
		synchronized (localNodeSentMessageTypes) {
			return new Hashtable<String,Long>(localNodeSentMessageTypes);
		}
	}

	public Hashtable<String,Long> getLocalNodeReceivedMessagesFromStatistic() {
		// Must be synchronized *during the copy*
		synchronized (localNodeReceivedMessageTypes) {
			return new Hashtable<String,Long>(localNodeReceivedMessageTypes);
		}
	}

	synchronized USK getARK() {
		return myARK;
	}

	public void gotARK(SimpleFieldSet fs, long fetchedEdition) {
		try {
			synchronized(this) {
				handshakeCount = 0;
				// edition +1 because we store the ARK edition that we want to fetch.
				if(myARK.suggestedEdition < fetchedEdition + 1)
					myARK = myARK.copy(fetchedEdition + 1);
			}
			processNewNoderef(fs, true, false);
		} catch(FSParseException e) {
			Logger.error(this, "Invalid ARK update: " + e, e);
			// This is ok as ARKs are limited to 4K anyway.
			Logger.error(this, "Data was: \n" + fs.toString());
			synchronized(this) {
				handshakeCount = PeerNode.MAX_HANDSHAKE_COUNT;
			}
		}
	}

	public synchronized int getPeerNodeStatus() {
		return peerNodeStatus;
	}

	public String getPeerNodeStatusString() {
		int status = getPeerNodeStatus();
		return getPeerNodeStatusString(status);
	}

	public static String getPeerNodeStatusString(int status) {
		if(status == PeerManager.PEER_NODE_STATUS_CONNECTED)
			return "CONNECTED";
		if(status == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF)
			return "BACKED OFF";
		if(status == PeerManager.PEER_NODE_STATUS_TOO_NEW)
			return "TOO NEW";
		if(status == PeerManager.PEER_NODE_STATUS_TOO_OLD)
			return "TOO OLD";
		if(status == PeerManager.PEER_NODE_STATUS_DISCONNECTED)
			return "DISCONNECTED";
		if(status == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED)
			return "NEVER CONNECTED";
		if(status == PeerManager.PEER_NODE_STATUS_DISABLED)
			return "DISABLED";
		if(status == PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM)
			return "CLOCK PROBLEM";
		if(status == PeerManager.PEER_NODE_STATUS_CONN_ERROR)
			return "CONNECTION ERROR";
		if(status == PeerManager.PEER_NODE_STATUS_ROUTING_DISABLED)
			return "ROUTING DISABLED";
		if(status == PeerManager.PEER_NODE_STATUS_LISTEN_ONLY)
			return "LISTEN ONLY";
		if(status == PeerManager.PEER_NODE_STATUS_LISTENING)
			return "LISTENING";
		if(status == PeerManager.PEER_NODE_STATUS_BURSTING)
			return "BURSTING";
		if(status == PeerManager.PEER_NODE_STATUS_DISCONNECTING)
			return "DISCONNECTING";
		return "UNKNOWN STATUS";
	}

	public String getPeerNodeStatusCSSClassName() {
		int status = getPeerNodeStatus();
		return getPeerNodeStatusCSSClassName(status);
	}

	public static String getPeerNodeStatusCSSClassName(int status) {
		if(status == PeerManager.PEER_NODE_STATUS_CONNECTED)
			return "peer_connected";
		else if(status == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF)
			return "peer_backed_off";
		else if(status == PeerManager.PEER_NODE_STATUS_TOO_NEW)
			return "peer_too_new";
		else if(status == PeerManager.PEER_NODE_STATUS_TOO_OLD)
			return "peer_too_old";
		else if(status == PeerManager.PEER_NODE_STATUS_DISCONNECTED)
			return "peer_disconnected";
		else if(status == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED)
			return "peer_never_connected";
		else if(status == PeerManager.PEER_NODE_STATUS_DISABLED)
			return "peer_disabled";
		else if(status == PeerManager.PEER_NODE_STATUS_ROUTING_DISABLED)
			return "peer_routing_disabled";
		else if(status == PeerManager.PEER_NODE_STATUS_BURSTING)
			return "peer_bursting";
		else if(status == PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM)
			return "peer_clock_problem";
		else if(status == PeerManager.PEER_NODE_STATUS_LISTENING)
			return "peer_listening";
		else if(status == PeerManager.PEER_NODE_STATUS_LISTEN_ONLY)
			return "peer_listen_only";
		else if(status == PeerManager.PEER_NODE_STATUS_DISCONNECTING)
			return "peer_disconnecting";
		else
			return "peer_unknown_status";
	}

	protected synchronized int getPeerNodeStatus(long now, long routingBackedOffUntilRT, long localRoutingBackedOffUntilBulk, boolean overPingTime) {
		checkConnectionsAndTrackers();
		if(disconnecting)
			return PeerManager.PEER_NODE_STATUS_DISCONNECTING;
		if(isRoutable()) {  // Function use also updates timeLastConnected and timeLastRoutable
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_CONNECTED;
			if(overPingTime && (lastRoutingBackoffReasonRT == null || now >= routingBackedOffUntilRT)) {
				lastRoutingBackoffReasonRT = "TooHighPing";
			}
			if(now < routingBackedOffUntilRT || overPingTime) {
				peerNodeStatus = PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF;
				if(!lastRoutingBackoffReasonRT.equals(previousRoutingBackoffReasonRT) || (previousRoutingBackoffReasonRT == null)) {
					if(previousRoutingBackoffReasonRT != null) {
						peers.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReasonRT, this, true);
					}
					peers.addPeerNodeRoutingBackoffReason(lastRoutingBackoffReasonRT, this, true);
					previousRoutingBackoffReasonRT = lastRoutingBackoffReasonRT;
				}
			} else {
				if(previousRoutingBackoffReasonRT != null) {
					peers.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReasonRT, this, true);
					previousRoutingBackoffReasonRT = null;
				}
			}
			
			if(overPingTime && (lastRoutingBackoffReasonBulk == null || now >= routingBackedOffUntilBulk)) {
				lastRoutingBackoffReasonBulk = "TooHighPing";
			}
			if(now < routingBackedOffUntilBulk || overPingTime) {
				peerNodeStatus = PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF;
				if(!lastRoutingBackoffReasonBulk.equals(previousRoutingBackoffReasonBulk) || (previousRoutingBackoffReasonBulk == null)) {
					if(previousRoutingBackoffReasonBulk != null) {
						peers.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReasonBulk, this, false);
					}
					peers.addPeerNodeRoutingBackoffReason(lastRoutingBackoffReasonBulk, this, false);
					previousRoutingBackoffReasonBulk = lastRoutingBackoffReasonBulk;
				}
			} else {
				if(previousRoutingBackoffReasonBulk != null) {
					peers.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReasonBulk, this, false);
					previousRoutingBackoffReasonBulk = null;
				}
			}

		} else if(isConnected() && bogusNoderef)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_CONN_ERROR;
		else if(isConnected() && unroutableNewerVersion)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_TOO_NEW;
		else if(isConnected && unroutableOlderVersion)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_TOO_OLD;
		else if(isConnected && disableRouting)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_ROUTING_DISABLED;
		else if(isConnected && Math.abs(clockDelta) > MAX_CLOCK_DELTA)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM;
		else if(neverConnected)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED;
		else if(isBursting)
			return PeerManager.PEER_NODE_STATUS_BURSTING;
		else
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_DISCONNECTED;
		if(!isConnected && (previousRoutingBackoffReasonRT != null)) {
			peers.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReasonRT, this, true);
			previousRoutingBackoffReasonRT = null;
		}
		if(!isConnected && (previousRoutingBackoffReasonBulk != null)) {
			peers.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReasonBulk, this, false);
			previousRoutingBackoffReasonBulk = null;
		}
		return peerNodeStatus;
	}

	public int setPeerNodeStatus(long now) {
		return setPeerNodeStatus(now, false);
	}

	public int setPeerNodeStatus(long now, boolean noLog) {
		long localRoutingBackedOffUntilRT = getRoutingBackedOffUntil(true);
		long localRoutingBackedOffUntilBulk = getRoutingBackedOffUntil(true);
		int oldPeerNodeStatus;
		
		long threshold = maxPeerPingTime();
		
		synchronized(this) {
			oldPeerNodeStatus = peerNodeStatus;
			peerNodeStatus = getPeerNodeStatus(now, localRoutingBackedOffUntilRT, localRoutingBackedOffUntilBulk, averagePingTime() > threshold);

			if(peerNodeStatus != oldPeerNodeStatus && recordStatus()) {
				peers.removePeerNodeStatus(oldPeerNodeStatus, this, noLog);
				peers.addPeerNodeStatus(peerNodeStatus, this, noLog);
			}

		}
		if(logMINOR) Logger.minor(this, "Peer node status now "+peerNodeStatus+" was "+oldPeerNodeStatus);
		if(peerNodeStatus!=oldPeerNodeStatus){
			notifyPeerNodeStatusChangeListeners();
		}
		if(peerNodeStatus == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
			long delta = Math.max(localRoutingBackedOffUntilRT, localRoutingBackedOffUntilBulk) - now + 1;
			if(delta > 0)
				node.ticker.queueTimedJob(checkStatusAfterBackoff, "Update status for "+this, delta, true, true);
		}
		return peerNodeStatus;
	}
	
	private final Runnable checkStatusAfterBackoff;

	public abstract boolean recordStatus();

	private synchronized void checkConnectionsAndTrackers() {
		if(isConnected) {
			if(currentTracker == null) {
				if(unverifiedTracker != null) {
					if(unverifiedTracker.packets.isDeprecated())
						Logger.error(this, "Connected but primary tracker is null and unverified is deprecated ! " + unverifiedTracker + " for " + this, new Exception("debug"));
					else if(logMINOR)
						Logger.minor(this, "Connected but primary tracker is null, but unverified = " + unverifiedTracker + " for " + this, new Exception("debug"));
				} else {
					Logger.error(this, "Connected but both primary and unverified are null on " + this, new Exception("debug"));
				}
			} else if(currentTracker.packets.isDeprecated()) {
				if(unverifiedTracker != null) {
					if(unverifiedTracker.packets.isDeprecated())
						Logger.error(this, "Connected but primary tracker is deprecated, unverified is deprecated: primary=" + currentTracker + " unverified: " + unverifiedTracker + " for " + this, new Exception("debug"));
					else if(logMINOR)
						Logger.minor(this, "Connected, primary tracker deprecated, unverified is valid, " + unverifiedTracker + " for " + this, new Exception("debug"));
				} else {
					// !!!!!!!
					Logger.error(this, "Connected but primary tracker is deprecated and unverified tracker is null on " + this+" primary tracker = "+currentTracker, new Exception("debug"));
					isConnected = false;
				}
			}
		}
	}

	public String getIdentityString() {
		return identityAsBase64String;
	}

	public boolean isFetchingARK() {
		return arkFetcher != null;
	}

	public synchronized int getHandshakeCount() {
		return handshakeCount;
	}

	/**
	 * Queries the Version class to determine if this peers advertised build-number is either too-old or
	 * to new for the routing of requests.
	 */
	synchronized void updateVersionRoutablity() {
			unroutableOlderVersion = forwardInvalidVersion();
			unroutableNewerVersion = reverseInvalidVersion();
	}

	/**
	 * Will return true if routing to this node is either explictly disabled, or disabled due to
	 * noted incompatiblity in build-version numbers.
	 * Logically: "not(isRoutable())", but will return false even if disconnected (meaning routing is not disabled).
	 */
	public synchronized boolean noLongerRoutable() {
		if(unroutableNewerVersion || unroutableOlderVersion || disableRouting)
			return true;
		return false;
	}

	final void invalidate(long now) {
		synchronized(this) {
			isRoutable = false;
		}
		Logger.normal(this, "Invalidated " + this);
		setPeerNodeStatus(System.currentTimeMillis());
	}

	public void maybeOnConnect() {
		if(wasDisconnected && isConnected()) {
			synchronized(this) {
				wasDisconnected = false;
			}
			onConnect();
		} else if(!isConnected()) {
			synchronized(this) {
				wasDisconnected = true;
			}
		}
	}

	/**
	 * A method to be called once at the beginning of every time isConnected() is true
	 */
	protected void onConnect() {
		synchronized(this) {
			sendingUOMMainJar = false;
			sendingUOMExtJar = false;
		}
		OpennetManager om = node.getOpennet();
		if(om != null)
			om.dropExcessPeers();
	}

	public void onFound(USK origUSK, long edition, FetchResult result) {
		if(isConnected() || myARK.suggestedEdition > edition) {
			result.asBucket().free();
			return;
		}

		byte[] data;
		try {
			data = result.asByteArray();
		} catch(IOException e) {
			Logger.error(this, "I/O error reading fetched ARK: " + e, e);
			result.asBucket().free();
			return;
		}

		String ref;
		try {
			ref = new String(data, "UTF-8");
		} catch(UnsupportedEncodingException e) {
			result.asBucket().free();
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}

		SimpleFieldSet fs;
		try {
			fs = new SimpleFieldSet(ref, false, true);
			if(logMINOR)
				Logger.minor(this, "Got ARK for " + this);
			gotARK(fs, edition);
		} catch(IOException e) {
			// Corrupt ref.
			Logger.error(this, "Corrupt ARK reference? Fetched " + myARK.copy(edition) + " got while parsing: " + e + " from:\n" + ref, e);
		}
		result.asBucket().free();

	}

	public synchronized boolean noContactDetails() {
		return handshakeIPs == null || handshakeIPs.length == 0;
	}

	private synchronized void reportIncomingBytes(int length) {
		totalBytesIn += length;
		totalBytesExchangedWithCurrentTracker += length;
	}

	private synchronized void reportOutgoingBytes(int length) {
		totalBytesOut += length;
		totalBytesExchangedWithCurrentTracker += length;
	}

	public synchronized long getTotalInputBytes() {
		return totalBytesIn;
	}

	public synchronized long getTotalOutputBytes() {
		return totalBytesOut;
	}

	public synchronized long getTotalInputSinceStartup() {
		return totalInputSinceStartup;
	}

	public synchronized long getTotalOutputSinceStartup() {
		return totalOutputSinceStartup;
	}

	public boolean isSignatureVerificationSuccessfull() {
		return isSignatureVerificationSuccessfull;
	}

	public void checkRoutableConnectionStatus() {
		synchronized(this) {
			if(isRoutable())
				hadRoutableConnectionCount += 1;
			routableConnectionCheckCount += 1;
			// prevent the average from moving too slowly by capping the checkcount to 200000,
			// which, at 7 seconds between counts, works out to about 2 weeks.  This also prevents
			// knowing how long we've had a particular peer long term.
			if(routableConnectionCheckCount >= 200000) {
				// divide both sides by the same amount to keep the same ratio
				hadRoutableConnectionCount = hadRoutableConnectionCount / 2;
				routableConnectionCheckCount = routableConnectionCheckCount / 2;
			}
		}
	}

	public synchronized double getPercentTimeRoutableConnection() {
		if(hadRoutableConnectionCount == 0)
			return 0.0;
		return ((double) hadRoutableConnectionCount) / routableConnectionCheckCount;
	}

	public int getVersionNumber() {
		return Version.getArbitraryBuildNumber(getVersion(), -1);
	}

	private final PacketThrottle _lastThrottle = new PacketThrottle(Node.PACKET_SIZE);

	public PacketThrottle getThrottle() {
		return _lastThrottle;
	}

	/**
	 * Select the most appropriate negType, taking the user's preference into account
	 * order matters
	 *
	 * @param mangler
	 * @return -1 if no common negType has been found
	 */
	public int selectNegType(OutgoingPacketMangler mangler) {
		int[] hisNegTypes;
		int[] myNegTypes = mangler.supportedNegTypes(false);
		synchronized(this) {
			hisNegTypes = negTypes;
		}
		int bestNegType = -1;
		for(int i = 0; i < myNegTypes.length; i++) {
			int negType = myNegTypes[i];
			for(int j = 0; j < hisNegTypes.length; j++) {
				if(hisNegTypes[j] == negType) {
					bestNegType = negType;
					break;
				}
			}
		}
		return bestNegType;
	}

	/** Verify a hash */
	public boolean verify(byte[] hash, DSASignature sig) {
		return DSA.verify(peerPubKey, sig, new NativeBigInteger(1, hash), false);
	}

	public String userToString() {
		return "" + getPeer();
	}

	public void setTimeDelta(long delta) {
		synchronized(this) {
			clockDelta = delta;
			if(Math.abs(clockDelta) > MAX_CLOCK_DELTA)
				isRoutable = false;
		}
		setPeerNodeStatus(System.currentTimeMillis());
	}

	public long getClockDelta() {
		return clockDelta;
	}

	/** Offer a key to this node */
	public void offer(Key key) {
		byte[] keyBytes = key.getFullKey();
		// FIXME maybe the authenticator should be shorter than 32 bytes to save memory?
		byte[] authenticator = HMAC.macWithSHA256(node.failureTable.offerAuthenticatorKey, keyBytes, 32);
		Message msg = DMT.createFNPOfferKey(key, authenticator);
		try {
			sendAsync(msg, null, node.nodeStats.sendOffersCtr);
		} catch(NotConnectedException e) {
		// Ignore
		}
	}

	public OutgoingPacketMangler getOutgoingMangler() {
		return outgoingMangler;
	}

	public SocketHandler getSocketHandler() {
		return outgoingMangler.getSocketHandler();
	}

	/** Is this peer disabled? I.e. has the user explicitly disabled it? */
	public boolean isDisabled() {
		return false;
	}

	/** Is this peer allowed local addresses? If false, we will never connect to this peer via
	 * a local address even if it advertises them.
	 */
	public boolean allowLocalAddresses() {
		return this.outgoingMangler.alwaysAllowLocalAddresses();
	}

	/** Is this peer set to ignore source address? If so, we will always reply to the peer's official
	 * address, even if we get packets from somewhere else. @see DarknetPeerNode.isIgnoreSourcePort().
	 */
	public boolean isIgnoreSource() {
		return false;
	}

	/**
	 * Create a DarknetPeerNode or an OpennetPeerNode as appropriate
	 */
	public static PeerNode create(SimpleFieldSet fs, Node node2, NodeCrypto crypto, OpennetManager opennet, PeerManager manager, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		if(crypto.isOpennet)
			return new OpennetPeerNode(fs, node2, crypto, opennet, manager, true, mangler);
		else
			return new DarknetPeerNode(fs, node2, crypto, manager, true, mangler, null);
	}

	public byte[] getIdentity() {
		return identity;
	}

	public boolean neverConnected() {
		return neverConnected;
	}

	/** Called when a request or insert succeeds. Used by opennet. */
	public abstract void onSuccess(boolean insert, boolean ssk);

	/** Called when a delayed disconnect is occurring. Tell the node that it is being 
	 * disconnected, but that the process may take a while. After this point, requests
	 * will not be accepted from the peer nor routed to it. 
	 * @param dumpMessagesNow If true, immediately dump the message queue, since we are
	 * closing the connection due to some low level trouble e.g. not acknowledging. 
	 * We will continue to try to send anything already in flight, and it is possible to
	 * send more messages after this point, for instance the message telling it we are
	 * disconnecting, but see above - no requests will be routed across this connection. 
	 * @return True if we have already started disconnecting, false otherwise. */
	public boolean notifyDisconnecting(boolean dumpMessageQueue) {
		MessageItem[] messagesTellDisconnected = null;
		synchronized(this) {
			if(disconnecting) return true;
			disconnecting = true;
			jfkNoncesSent.clear();
			if(dumpMessageQueue) {
				// Reset the boot ID so that we get different trackers next time.
				myBootID = node.fastWeakRandom.nextLong();
				messagesTellDisconnected = grabQueuedMessageItems();
			}
		}
		setPeerNodeStatus(System.currentTimeMillis());
		if(messagesTellDisconnected != null) {
			if(logMINOR)
				Logger.minor(this, "Messages to dump: "+messagesTellDisconnected.length);
			for(MessageItem mi : messagesTellDisconnected) {
				mi.onDisconnect();
			}
		}
		return false;
	}

	/** Called to cancel a delayed disconnect. Always succeeds even if the node was not being
	 * disconnected. */
	public void forceCancelDisconnecting() {
		synchronized(this) {
			removed = false;
			if(!disconnecting)
				return;
			disconnecting = false;
		}
		setPeerNodeStatus(System.currentTimeMillis(), true);
	}

	/** Called when the peer is removed from the PeerManager */
	public void onRemove() {
		synchronized(this) {
			removed = true;
		}
		node.getTicker().removeQueuedJob(checkStatusAfterBackoff);
		disconnected(true, true);
		stopARKFetcher();
	}
	
	/** @return True if we have been removed from the peers list. */
	synchronized boolean cachedRemoved() {
		return removed;
	}

	public synchronized boolean isDisconnecting() {
		return disconnecting;
	}

	protected byte[] getJFKBuffer() {
		return jfkBuffer;
	}

	protected void setJFKBuffer(byte[] bufferJFK) {
		this.jfkBuffer = bufferJFK;
	}

	public int getSigParamsByteLength() {
		int bitLen = this.peerCryptoGroup.getQ().bitLength();
		int byteLen = bitLen / 8 + (bitLen % 8 != 0 ? 1 : 0);
		return byteLen;
	}

	// Recent packets sent/received
	// We record times and weak short hashes of the last 64 packets
	// sent/received. When we connect successfully, we send the data
	// on what packets we have sent, and the recipient can compare
	// this to their records of received packets to determine if there
	// is a problem, which usually indicates not being port forwarded.

	static final short TRACK_PACKETS = 64;
	private final long[] packetsSentTimes = new long[TRACK_PACKETS];
	private final long[] packetsRecvTimes = new long[TRACK_PACKETS];
	private final long[] packetsSentHashes = new long[TRACK_PACKETS];
	private final long[] packetsRecvHashes = new long[TRACK_PACKETS];
	private short sentPtr;
	private short recvPtr;
	private boolean sentTrackPackets;
	private boolean recvTrackPackets;

	public void reportIncomingPacket(byte[] buf, int offset, int length, long now) {
		reportIncomingBytes(length);
		long hash = Fields.longHashCode(buf, offset, length);
		synchronized(this) {
			packetsRecvTimes[recvPtr] = now;
			packetsRecvHashes[recvPtr] = hash;
			recvPtr++;
			if(recvPtr == TRACK_PACKETS) {
				recvPtr = 0;
				recvTrackPackets = true;
			}
		}
	}

	public void reportOutgoingPacket(byte[] buf, int offset, int length, long now) {
		reportOutgoingBytes(length);
		long hash = Fields.longHashCode(buf, offset, length);
		synchronized(this) {
			packetsSentTimes[sentPtr] = now;
			packetsSentHashes[sentPtr] = hash;
			sentPtr++;
			if(sentPtr == TRACK_PACKETS) {
				sentPtr = 0;
				sentTrackPackets = true;
			}
		}
	}

	/**
	 * @return a long[] consisting of two arrays, the first being packet times,
	 * the second being packet hashes.
	 */
	public synchronized long[][] getSentPacketTimesHashes() {
		short count = sentTrackPackets ? TRACK_PACKETS : sentPtr;
		long[] times = new long[count];
		long[] hashes = new long[count];
		if(!sentTrackPackets) {
			System.arraycopy(packetsSentTimes, 0, times, 0, sentPtr);
			System.arraycopy(packetsSentHashes, 0, hashes, 0, sentPtr);
		} else {
			System.arraycopy(packetsSentTimes, sentPtr, times, 0, TRACK_PACKETS - sentPtr);
			System.arraycopy(packetsSentTimes, 0, times, TRACK_PACKETS - sentPtr, sentPtr);
			System.arraycopy(packetsSentHashes, sentPtr, hashes, 0, TRACK_PACKETS - sentPtr);
			System.arraycopy(packetsSentHashes, 0, hashes, TRACK_PACKETS - sentPtr, sentPtr);
		}
		return new long[][]{times, hashes};
	}

	/**
	 * @return a long[] consisting of two arrays, the first being packet times,
	 * the second being packet hashes.
	 */
	public synchronized long[][] getRecvPacketTimesHashes() {
		short count = recvTrackPackets ? TRACK_PACKETS : recvPtr;
		long[] times = new long[count];
		long[] hashes = new long[count];
		if(!recvTrackPackets) {
			System.arraycopy(packetsRecvTimes, 0, times, 0, recvPtr);
			System.arraycopy(packetsRecvHashes, 0, hashes, 0, recvPtr);
		} else {
			System.arraycopy(packetsRecvTimes, recvPtr, times, 0, TRACK_PACKETS - recvPtr);
			System.arraycopy(packetsRecvTimes, 0, times, TRACK_PACKETS - recvPtr, recvPtr);
			System.arraycopy(packetsRecvHashes, recvPtr, hashes, 0, TRACK_PACKETS - recvPtr);
			System.arraycopy(packetsRecvHashes, 0, hashes, TRACK_PACKETS - recvPtr, recvPtr);
		}
		return new long[][]{times, hashes};
	}
	static final int SENT_PACKETS_MAX_TIME_AFTER_CONNECT = 5 * 60 * 1000;

	/**
	 * Handle an FNPSentPackets message
	 */
	public void handleSentPackets(Message m) {

		// IMHO it's impossible to make this work reliably on lossy connections, especially highly saturated upstreams.
		// If it was possible it would likely involve a lot of work, refactoring, voting between peers, marginal results,
		// very slow accumulation of data etc.

//		long now = System.currentTimeMillis();
//		synchronized(this) {
//			if(forceDisconnectCalled)
//				return;
//			/*
//			 * I've had some very strange results from seed clients!
//			 * One showed deltas of over 10 minutes... how is that possible? The PN wouldn't reconnect?!
//			 */
//			if(!isRealConnection())
//				return; // The packets wouldn't have been assigned to this PeerNode!
////			if(now - this.timeLastConnected < SENT_PACKETS_MAX_TIME_AFTER_CONNECT)
////				return;
//		}
//		long baseTime = m.getLong(DMT.TIME);
//		baseTime += this.clockDelta;
//		// Should be a reasonable approximation now
//		int[] timeDeltas = Fields.bytesToInts(((ShortBuffer) m.getObject(DMT.TIME_DELTAS)).getData());
//		long[] packetHashes = Fields.bytesToLongs(((ShortBuffer) m.getObject(DMT.HASHES)).getData());
//		long[] times = new long[timeDeltas.length];
//		for(int i = 0; i < times.length; i++)
//			times[i] = baseTime - timeDeltas[i];
//		long tolerance = 60 * 1000 + (Math.abs(timeDeltas[0]) / 20); // 1 minute or 5% of full interval
//		synchronized(this) {
//			// They are in increasing order
//			// Loop backwards
//			long otime = Long.MAX_VALUE;
//			long[][] sent = getRecvPacketTimesHashes();
//			long[] sentTimes = sent[0];
//			long[] sentHashes = sent[1];
//			short sentPtr = (short) (sent.length - 1);
//			short notFoundCount = 0;
//			short consecutiveNotFound = 0;
//			short longestConsecutiveNotFound = 0;
//			short ignoredUptimeCount = 0;
//			short found = 0;
//			//The arrays are constructed from received data, don't throw an ArrayIndexOutOfBoundsException if they are different sizes.
//			int shortestArray=times.length;
//			if (shortestArray > packetHashes.length)
//				shortestArray = packetHashes.length;
//			for(short i = (short) (shortestArray-1); i >= 0; i--) {
//				long time = times[i];
//				if(time > otime) {
//					Logger.error(this, "Inconsistent time order: [" + i + "]=" + time + " but [" + (i + 1) + "] is " + otime);
//					return;
//				} else
//					otime = time;
//				long hash = packetHashes[i];
//				// Search for the hash.
//				short match = -1;
//				// First try forwards
//				for(short j = sentPtr; j < sentTimes.length; j++) {
//					long ttime = sentTimes[j];
//					if(sentHashes[j] == hash) {
//						match = j;
//						sentPtr = j;
//						break;
//					}
//					if(ttime - time > tolerance)
//						break;
//				}
//				if(match == -1)
//					for(short j = (short) (sentPtr - 1); j >= 0; j--) {
//						long ttime = sentTimes[j];
//						if(sentHashes[j] == hash) {
//							match = j;
//							sentPtr = j;
//							break;
//						}
//						if(time - ttime > tolerance)
//							break;
//					}
//				if(match == -1) {
//					long mustHaveBeenUpAt = now - (int)(timeDeltas[i] * 1.1) - 100;
//					if(this.crypto.socket.getStartTime() > mustHaveBeenUpAt) {
//						ignoredUptimeCount++;
//					} else {
//						// Not found
//						consecutiveNotFound++;
//						notFoundCount++;
//					}
//				} else {
//					if(consecutiveNotFound > longestConsecutiveNotFound)
//						longestConsecutiveNotFound = consecutiveNotFound;
//					consecutiveNotFound = 0;
//					found++;
//				}
//			}
//			if(consecutiveNotFound > longestConsecutiveNotFound)
//				longestConsecutiveNotFound = consecutiveNotFound;
//			Logger.error(this, "Packets: "+packetHashes.length+" not found "+notFoundCount+" consecutive not found "+consecutiveNotFound+" longest consecutive not found "+longestConsecutiveNotFound+" ignored due to uptime: "+ignoredUptimeCount+" found: "+found);
//			if(longestConsecutiveNotFound > TRACK_PACKETS / 2) {
//				manyPacketsClaimedSentNotReceived = true;
//				timeManyPacketsClaimedSentNotReceived = now;
//				Logger.error(this, "" + consecutiveNotFound + " consecutive packets not found on " + userToString());
//				SocketHandler handler = outgoingMangler.getSocketHandler();
//				if(handler instanceof PortForwardSensitiveSocketHandler) {
//					((PortForwardSensitiveSocketHandler) handler).rescanPortForward();
//				}
//			}
//		}
//		if(manyPacketsClaimedSentNotReceived) {
//			outgoingMangler.setPortForwardingBroken();
//		}
	}
	private boolean manyPacketsClaimedSentNotReceived = false;

	synchronized boolean manyPacketsClaimedSentNotReceived() {
		return manyPacketsClaimedSentNotReceived;
	}

	static final int MAX_SIMULTANEOUS_ANNOUNCEMENTS = 1;
	static final int MAX_ANNOUNCE_DELAY = 1000;
	private long timeLastAcceptedAnnouncement;
	private long[] runningAnnounceUIDs = new long[0];

	public synchronized boolean shouldAcceptAnnounce(long uid) {
		long now = System.currentTimeMillis();
		if(runningAnnounceUIDs.length < MAX_SIMULTANEOUS_ANNOUNCEMENTS &&
				now - timeLastAcceptedAnnouncement > MAX_ANNOUNCE_DELAY) {
			long[] newList = new long[runningAnnounceUIDs.length + 1];
			if(runningAnnounceUIDs.length > 0)
				System.arraycopy(runningAnnounceUIDs, 0, newList, 0, runningAnnounceUIDs.length);
			newList[runningAnnounceUIDs.length] = uid;
			timeLastAcceptedAnnouncement = now;
			return true;
		} else {
			return false;
		}
	}

	public synchronized boolean completedAnnounce(long uid) {
		final int runningAnnounceUIDsLength = runningAnnounceUIDs.length;
		if(runningAnnounceUIDsLength < 1) return false;
		long[] newList = new long[runningAnnounceUIDsLength - 1];
		int x = 0;
		for(int i=0;i<runningAnnounceUIDs.length;i++) {
			if(i == runningAnnounceUIDs.length) return false;
			long l = runningAnnounceUIDs[i];
			if(l == uid) continue;
			newList[x++] = l;
		}
		runningAnnounceUIDs = newList;
		if(x < runningAnnounceUIDs.length) {
			newList = new long[x];
			System.arraycopy(runningAnnounceUIDs, 0, newList, 0, x);
			runningAnnounceUIDs = newList;
		}
		return true;
	}

	public synchronized long timeLastDisconnect() {
		return timeLastDisconnect;
	}

	/** Does this peernode want to be returned by for example PeerManager.getByPeer() ?
	 * False = seednode etc, never going to be routable. */
	public abstract boolean isRealConnection();

	/** Can we accept announcements from this node? */
	public boolean canAcceptAnnouncements() {
		return isOpennet() || node.passOpennetRefsThroughDarknet();
	}

	public boolean handshakeUnknownInitiator() {
		return false;
	}

	public int handshakeSetupType() {
		return -1;
	}

	public WeakReference<PeerNode> getWeakRef() {
		return myRef;
	}

	/**
	 * Get a single address to send a handshake to.
	 * The current code doesn't work well with multiple simulataneous handshakes.
	 * Alternates between valid values.
	 * (FIXME!)
	 */
	public Peer getHandshakeIP() {
		Peer[] localHandshakeIPs;
		if(!shouldSendHandshake()) {
			if(logMINOR) Logger.minor(this, "Not sending handshake to "+getPeer()+" because pn.shouldSendHandshake() returned false");
			return null;
		}
		long firstTime = System.currentTimeMillis();
		localHandshakeIPs = getHandshakeIPs();
		long secondTime = System.currentTimeMillis();
		if((secondTime - firstTime) > 1000)
			Logger.error(this, "getHandshakeIPs() took more than a second to execute ("+(secondTime - firstTime)+") working on "+userToString());
		if(localHandshakeIPs.length == 0) {
			long thirdTime = System.currentTimeMillis();
			if((thirdTime - secondTime) > 1000)
				Logger.error(this, "couldNotSendHandshake() (after getHandshakeIPs()) took more than a second to execute ("+(thirdTime - secondTime)+") working on "+userToString());
			return null;
		}
		long loopTime1 = System.currentTimeMillis();
		Vector<Peer> validIPs = new Vector<Peer>();
		for(int i=0;i<localHandshakeIPs.length;i++){
			Peer peer = localHandshakeIPs[i];
			FreenetInetAddress addr = peer.getFreenetAddress();
			if(!outgoingMangler.allowConnection(this, addr)) {
				if(logMINOR)
					Logger.minor(this, "Not sending handshake packet to "+peer+" for "+this);
			}
			if(peer.getAddress(false) == null) {
				if(logMINOR) Logger.minor(this, "Not sending handshake to "+localHandshakeIPs[i]+" for "+getPeer()+" because the DNS lookup failed or it's a currently unsupported IPv6 address");
				continue;
			}
			if(!peer.isRealInternetAddress(false, false, allowLocalAddresses())) {
				if(logMINOR) Logger.minor(this, "Not sending handshake to "+localHandshakeIPs[i]+" for "+getPeer()+" because it's not a real Internet address and metadata.allowLocalAddresses is not true");
				continue;
			}
			validIPs.add(peer);
		}
		Peer ret;
		if(validIPs.isEmpty()) {
			ret = null;
		} else if(validIPs.size() == 1) {
			ret = validIPs.get(0);
		} else {
			// Don't need to synchronize for this value as we're only called from one thread anyway.
			handshakeIPAlternator %= validIPs.size();
			ret = validIPs.get(handshakeIPAlternator);
			handshakeIPAlternator++;
		}
		long loopTime2 = System.currentTimeMillis();
		if((loopTime2 - loopTime1) > 1000)
			Logger.normal(this, "loopTime2 is more than a second after loopTime1 ("+(loopTime2 - loopTime1)+") working on "+userToString());
		return ret;
	}

	private int handshakeIPAlternator;

	public void sendNodeToNodeMessage(SimpleFieldSet fs, int n2nType, boolean includeSentTime, long now, boolean queueOnNotConnected) {
		fs.putOverwrite("n2nType", Integer.toString(n2nType));
		if(includeSentTime) {
			fs.put("sentTime", now);
		}
		try {
			Message n2nm;
			n2nm = DMT.createNodeToNodeMessage(
					n2nType, fs.toString().getBytes("UTF-8"));
			try {
				sendAsync(n2nm, null, node.nodeStats.nodeToNodeCounter);
			} catch (NotConnectedException e) {
				if(includeSentTime) {
					fs.removeValue("sentTime");
				}
				if(isDarknet() && queueOnNotConnected) {
					queueN2NM(fs);
				}
			}
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}
	}

	/**
	 * A method to queue an N2NM in a extra peer data file, only implemented by DarknetPeerNode
	 */
	public void queueN2NM(SimpleFieldSet fs) {
		// Do nothing in the default impl
	}

	/**
	 * Return the relevant local node reference related to this peer's type
	 */
	protected SimpleFieldSet getLocalNoderef() {
		return crypto.exportPublicFieldSet();
	}

	/**
	 * A method to be called after completing a handshake to send the
	 * newly connected peer, as a differential node reference, the
	 * parts of our node reference not needed for handshake.
	 * Should only be called by completedHandshake() after we're happy
	 * with the connection
	 */
	protected void sendConnectedDiffNoderef() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		SimpleFieldSet nfs = getLocalNoderef();
		if(null == nfs) return;
		if(null != nfs.get("ark.pubURI")) {
			fs.putOverwrite("ark.pubURI", nfs.get("ark.pubURI"));
		}
		if(null != nfs.get("ark.number")) {
			fs.putOverwrite("ark.number", nfs.get("ark.number"));
		}
		if(isDarknet() && null != nfs.get("myName")) {
			fs.putOverwrite("myName", nfs.get("myName"));
		}
		String[] physicalUDPEntries = nfs.getAll("physical.udp");
		if(physicalUDPEntries != null) {
			fs.putOverwrite("physical.udp", physicalUDPEntries);
		}
		if(!fs.isEmpty()) {
			if(logMINOR) Logger.minor(this, "fs is '" + fs.toString() + "'");
			sendNodeToNodeMessage(fs, Node.N2N_MESSAGE_TYPE_DIFFNODEREF, false, 0, false);
		} else {
			if(logMINOR) Logger.minor(this, "fs is empty");
		}
	}

	int assignedNetworkID;
	int providedNetworkID;
	NetworkIDManager.PeerNetworkGroup networkGroup;

	void handleFNPNetworkID(Message m) {
		int got=m.getInt(DMT.UID);
		if (logMINOR) Logger.minor(this, "now peer thinks he is in network "+got);
		if (providedNetworkID!=got && assignedNetworkID!=got) {
			providedNetworkID=got;
			node.netid.onPeerNodeChangedNetworkID(this);
		} else {
			providedNetworkID=got;
		}
	}

	void sendFNPNetworkID(ByteCounter ctr) throws NotConnectedException {
		if (assignedNetworkID!=0)
			sendAsync(DMT.createFNPNetworkID(assignedNetworkID), null, ctr);
	}

	public boolean shouldThrottle() {
		return shouldThrottle(getPeer(), node);
	}

	public static boolean shouldThrottle(Peer peer, Node node) {
		if(node.throttleLocalData) return true;
		if(peer == null) return true; // presumably
		InetAddress addr = peer.getAddress(false);
		if(addr == null) return true; // presumably
		return IPUtil.isValidAddress(addr, false);
	}

	static final double MAX_RTO = 60*1000;
	static final double MIN_RTO = 1000;
	private int consecutiveRTOBackoffs;
	
	// Clock generally has 20ms granularity or better, right?
	// FIXME determine the clock granularity.
	private static int CLOCK_GRANULARITY = 20;
	
	public void reportPing(long t) {
		this.pingAverage.report(t);
		synchronized(this) {
			consecutiveRTOBackoffs = 0;
			// Update RTT according to RFC 2988.
			if(!reportedRTT) {
				double oldRTO = RTO;
				// Initialize
				SRTT = t;
				RTTVAR = t / 2;
				RTO = SRTT + Math.max(CLOCK_GRANULARITY, RTTVAR * 4);
				// RFC 2988 specifies a 1 second minimum RTT, mostly due to legacy issues,
				// but given that Freenet is mostly used on very slow upstream links, it 
				// probably makes sense for us too for now, to avoid excessive retransmits.
				// FIXME !!!
				if(RTO < MIN_RTO)
					RTO = MIN_RTO;
				if(RTO > MAX_RTO)
					RTO = MAX_RTO;
				reportedRTT = true;
				if(logMINOR) Logger.minor(this, "Received first packet on "+shortToString()+" setting RTO to "+RTO);
				if(oldRTO > RTO) {
					// We have backed off
					if(logMINOR) Logger.minor(this, "Received first packet after backing off on resend. RTO is "+RTO+" but was "+oldRTO);
					// FIXME: do something???
				}
			} else {
				// Update
				RTTVAR = 0.75 * RTTVAR + 0.25 * Math.abs(SRTT - t);
				SRTT = 0.875 * SRTT + 0.125 * t;
				RTO = SRTT + Math.max(CLOCK_GRANULARITY, RTTVAR * 4);
				// RFC 2988 specifies a 1 second minimum RTT, mostly due to legacy issues,
				// but given that Freenet is mostly used on very slow upstream links, it 
				// probably makes sense for us too for now, to avoid excessive retransmits.
				// FIXME !!!
				if(RTO < MIN_RTO)
					RTO = MIN_RTO;
				if(RTO > MAX_RTO)
					RTO = MAX_RTO;
			}
			if(logMINOR) Logger.minor(this, "Reported ping "+t+" avg is now "+pingAverage.currentValue()+" RTO is "+RTO+" SRTT is "+SRTT+" RTTVAR is "+RTTVAR+" for "+shortToString());
		}
	}
	
	/**
	 * RFC 2988:
	 *    Note that a TCP implementation MAY clear SRTT and RTTVAR after
	 *    backing off the timer multiple times as it is likely that the
	 *    current SRTT and RTTVAR are bogus in this situation.  Once SRTT and
	 *    RTTVAR are cleared they should be initialized with the next RTT
	 *    sample taken per (2.2) rather than using (2.3).
	 */
	static final int MAX_CONSECUTIVE_RTO_BACKOFFS = 5;
	
	public synchronized void backoffOnResend() {
		if(RTO >= MAX_RTO) {
			Logger.error(this, "Major packet loss on "+this+" - RTO is already at limit and still losing packets!");
		}
		RTO = RTO * 2;
		if(RTO > MAX_RTO)
			RTO = MAX_RTO;
		consecutiveRTOBackoffs++;
		if(consecutiveRTOBackoffs > MAX_CONSECUTIVE_RTO_BACKOFFS) {
			Logger.warning(this, "Resetting RTO for "+this+" after "+consecutiveRTOBackoffs+" consecutive backoffs due to packet loss");
			consecutiveRTOBackoffs = 0;
			reportedRTT = false;
		}
		if(logMINOR) Logger.minor(this, "Backed off on resend, RTO is now "+RTO+" for "+shortToString()+" consecutive RTO backoffs is "+consecutiveRTOBackoffs);
	}

	private long resendBytesSent;

	public final ByteCounter resendByteCounter = new ByteCounter() {

		public void receivedBytes(int x) {
			// Ignore
		}

		public void sentBytes(int x) {
			synchronized(PeerNode.this) {
				resendBytesSent += x;
			}
			node.nodeStats.resendByteCounter.sentBytes(x);
		}

		public void sentPayload(int x) {
			// Ignore
		}

	};

	public long getResendBytesSent() {
		return resendBytesSent;
	}

	public MessageItem sendThrottledMessage(Message msg, int packetSize, ByteCounter ctr, int timeout, boolean blockForSend, AsyncMessageCallback callback) throws NotConnectedException, WaitedTooLongException, SyncSendWaitedTooLongException, PeerRestartedException {
		long deadline = System.currentTimeMillis() + timeout;
		if(logMINOR) Logger.minor(this, "Sending throttled message with timeout "+timeout+" packet size "+packetSize+" to "+shortToString());
		return getThrottle().sendThrottledMessage(msg, this, packetSize, ctr, deadline, blockForSend, callback, msg.getPriority() == DMT.PRIORITY_REALTIME_DATA);
	}

	/**
	 * Should this peer be disconnected and removed immediately?
	 */
	public boolean shouldDisconnectAndRemoveNow() {
		return false;
	}

	public void setUptime(byte uptime2) {
		this.uptime = uptime2;
	}

	public short getUptime() {
		return (short) (uptime & 0xFF);
	}

	public void incrementNumberOfSelections(long time) {
		// TODO: reimplement with a bit field to spare memory
		synchronized(this) {
			countSelectionsSinceConnected++;
		}
	}

	/**
	 * @return The rate at which this peer has been selected since it connected.
	 */
	public synchronized double selectionRate() {
		long timeSinceConnected = System.currentTimeMillis() - this.connectedTime;
		// Avoid bias due to short uptime.
		if(timeSinceConnected < 10*1000) return 0.0;
		return countSelectionsSinceConnected / (double) timeSinceConnected;
	}

	private volatile long offeredMainJarVersion;

	public void setMainJarOfferedVersion(long mainJarVersion) {
		offeredMainJarVersion = mainJarVersion;
	}

	public long getMainJarOfferedVersion() {
		return offeredMainJarVersion;
	}

	private volatile long offeredExtJarVersion;

	public void setExtJarOfferedVersion(long extJarVersion) {
		offeredExtJarVersion = extJarVersion;
	}

	public long getExtJarOfferedVersion() {
		return offeredExtJarVersion;
	}

	/**
	 * Maybe send something. A SINGLE PACKET.
	 * Don't send everything at once, for two reasons:
	 * 1. It is possible for a node to have a very long backlog.
	 * 2. Sometimes sending a packet can take a long time.
	 * 3. In the near future PacketSender will be responsible for output bandwidth
	 * throttling.
	 * So it makes sense to send a single packet and round-robin.
	 * @param now
	 * @param rpiTemp
	 * @param rpiTemp
	 * @throws BlockedTooLongException
	 */
	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp, boolean ackOnly) throws BlockedTooLongException {
		PacketFormat pf;
		synchronized(this) {
			if(packetFormat == null) return false;
			pf = packetFormat;
		}
		return pf.maybeSendPacket(now, rpiTemp, rpiIntTemp, ackOnly);
	}

	/**
	 * @return The ID of a reusable PacketTracker if there is one, otherwise -1.
	 */
	public long getReusableTrackerID() {
		SessionKey cur;
		synchronized(this) {
			cur = currentTracker;
		}
		if(cur == null) {
			if(logMINOR) Logger.minor(this, "getReusableTrackerID(): cur = null on "+this);
			return -1;
		}
		if(cur.packets.isDeprecated()) {
			if(logMINOR) Logger.minor(this, "getReusableTrackerID(): cur.packets.isDeprecated on "+this);
			return -1;
		}
		if(logMINOR) Logger.minor(this, "getReusableTrackerID(): "+cur.packets.trackerID+" on "+this);
		return cur.packets.trackerID;
	}

	private long lastFailedRevocationTransfer;
	/** Reset on disconnection */
	private int countFailedRevocationTransfers;

	public void failedRevocationTransfer() {
		lastAttemptedHandshakeIPUpdateTime = System.currentTimeMillis();
		countFailedRevocationTransfers++;
	}

	public int countFailedRevocationTransfers() {
		return countFailedRevocationTransfers;
	}

	/** Registers a listener that will be notified when status changes. Only the WeakReference of it is stored, so there is no need for deregistering
	 * @param listener - The listener to be registered*/
	public void registerPeerNodeStatusChangeListener(PeerManager.PeerStatusChangeListener listener){
		listeners.add(listener);
	}

	/** Notifies the listeners that status has been changed*/
	private void notifyPeerNodeStatusChangeListeners(){
		synchronized (listeners) {
			for(PeerManager.PeerStatusChangeListener l:listeners){
				l.onPeerStatusChange();
			}
		}
	}


	public boolean isLowUptime() {
		return getUptime() < Node.MIN_UPTIME_STORE_KEY;
	}

	public void setAddedReason(ConnectionType connectionType) {
		// Do nothing.
	}

	public synchronized ConnectionType getAddedReason() {
		return null;
	}
	
	private final Object routedToLock = new Object();
	
	final LoadSender loadSenderRealTime = new LoadSender(true);
	final LoadSender loadSenderBulk = new LoadSender(false);
	
	class LoadSender {
	
		LoadSender(boolean realTimeFlag) {
			this.realTimeFlag = realTimeFlag;
		}
		
		public void onDisconnect() {
			this.lastSentAllocationInput = 0;
			this.lastSentAllocationOutput = 0;
			this.timeLastSentAllocationNotice = -1;
			this.lastFullStats = null;
		}

		private int lastSentAllocationInput;
		private int lastSentAllocationOutput;
		private long timeLastSentAllocationNotice;
		private long countAllocationNotices;
		private PeerLoadStats lastFullStats;
		private final boolean realTimeFlag;
		private boolean sendASAP;
		
		public void onSetPeerAllocation(boolean input, int thisAllocation, int transfersPerInsert) {
			
			boolean mustSend = false;
			// FIXME review constants, how often are allocations actually sent?
			long now = System.currentTimeMillis();
			synchronized(this) {
				int last = input ? lastSentAllocationInput : lastSentAllocationOutput;
				if(now - timeLastSentAllocationNotice > 5000) {
					if(logMINOR) Logger.minor(this, "Last sent allocation "+TimeUtil.formatTime(now - timeLastSentAllocationNotice));
					mustSend = true;
				} else {
					if(thisAllocation > last * 1.05) {
						if(logMINOR) Logger.minor(this, "Last allocation was "+last+" this is "+thisAllocation);
						mustSend = true;
					} else if(thisAllocation < last * 0.9) { 
						if(logMINOR) Logger.minor(this, "Last allocation was "+last+" this is "+thisAllocation);
						mustSend = true;
					}
				}
				if(!mustSend) return;
				sendASAP = true;
			}
			if(!mustSend) return;
		}
		
		Message makeLoadStats(long now, int transfersPerInsert, boolean noRemember) {
			PeerLoadStats stats = node.nodeStats.createPeerLoadStats(PeerNode.this, transfersPerInsert, realTimeFlag);
			synchronized(this) {
				lastSentAllocationInput = (int) stats.inputBandwidthPeerLimit;
				lastSentAllocationOutput = (int) stats.outputBandwidthPeerLimit;
				if(!noRemember) {
					if(lastFullStats != null && lastFullStats.equals(stats)) return null;
					lastFullStats = stats;
				}
				timeLastSentAllocationNotice = now;
				countAllocationNotices++;
				if(logMINOR) Logger.minor(this, "Sending allocation notice to "+this+" allocation is "+lastSentAllocationInput+" input "+lastSentAllocationOutput+" output.");
			}
			Message msg = DMT.createFNPPeerLoadStatus(stats);
			return msg;
		}

		public synchronized boolean grabSendASAP() {
			boolean send = sendASAP;
			sendASAP = false;
			return send;
		}

		public synchronized void setSendASAP() {
			sendASAP = true;
		}
	}
	
	void removeUIDsFromMessageQueues(Long[] list) {
		this.messageQueue.removeUIDsFromMessageQueues(list);
	}
	
	public void onSetPeerAllocation(boolean input, int thisAllocation, int transfersPerInsert, boolean realTime) {
		(realTime ? loadSenderRealTime : loadSenderBulk).onSetPeerAllocation(input, thisAllocation, transfersPerInsert);
	}

	public class IncomingLoadSummaryStats {
		public IncomingLoadSummaryStats(int totalRequests,
				double outputBandwidthPeerLimit,
				double inputBandwidthPeerLimit,
				double outputBandwidthTotalLimit,
				double inputBandwidthTotalLimit,
				double usedOutput,
				double usedInput,
				double othersUsedOutput,
				double othersUsedInput) {
			runningRequestsTotal = totalRequests;
			peerCapacityOutputBytes = (int)outputBandwidthPeerLimit;
			peerCapacityInputBytes = (int)inputBandwidthPeerLimit;
			totalCapacityOutputBytes = (int)outputBandwidthTotalLimit;
			totalCapacityInputBytes = (int)inputBandwidthTotalLimit;
			usedCapacityOutputBytes = (int) usedOutput;
			usedCapacityInputBytes = (int) usedInput;
			othersUsedCapacityOutputBytes = (int) othersUsedOutput;
			othersUsedCapacityInputBytes = (int) othersUsedInput;
		}
		
		public final int runningRequestsTotal;
		public final int peerCapacityOutputBytes;
		public final int peerCapacityInputBytes;
		public final int totalCapacityOutputBytes;
		public final int totalCapacityInputBytes;
		public final int usedCapacityOutputBytes;
		public final int usedCapacityInputBytes;
		public final int othersUsedCapacityOutputBytes;
		public final int othersUsedCapacityInputBytes;
	}
	
	enum RequestLikelyAcceptedState {
		GUARANTEED, // guaranteed to be accepted, under the per-peer guaranteed limit
		LIKELY, // likely to be accepted even though above the per-peer guaranteed limit, as overall is below the overall lower limit
		UNLIKELY, // not likely to be accepted; peer is over the per-peer guaranteed limit, and global is over the overall lower limit
		UNKNOWN // no data but accepting anyway
	}
	
	// FIXME add LOW_CAPACITY/BROKEN. Set this when the published capacity is way below the median.
	// FIXME will need to calculate the median first!
	
	OutputLoadTracker outputLoadTrackerRealTime = new OutputLoadTracker(true);
	OutputLoadTracker outputLoadTrackerBulk = new OutputLoadTracker(false);
	
	public OutputLoadTracker outputLoadTracker(boolean realTime) {
		return realTime ? outputLoadTrackerRealTime : outputLoadTrackerBulk;
	}

	public void reportLoadStatus(PeerLoadStats stat) {
		outputLoadTracker(stat.realTime).reportLoadStatus(stat);
	}
	
	public static class SlotWaiter {
		
		private final HashSet<PeerNode> waitingFor;
		private PeerNode acceptedBy;
		private RequestLikelyAcceptedState acceptedState;
		final UIDTag tag;
		final boolean offeredKey;
		final RequestType requestType;
		private boolean failed;
		final boolean realTime;
		
		SlotWaiter(UIDTag tag, RequestType type, PeerNode initial, boolean offeredKey, boolean realTime) {
			this.tag = tag;
			this.requestType = type;
			this.offeredKey = offeredKey;
			this.waitingFor = new HashSet<PeerNode>();
			this.waitingFor.add(initial);
			this.realTime = realTime;
		}
		
		public void addWaitingFor(PeerNode peer) {
			synchronized(this) {
				if(acceptedBy != null) return;
				waitingFor.add(peer);
			}
			peer.outputLoadTracker(realTime).queueSlotWaiter(this);
		}
		
		void onWaited(PeerNode peer, RequestLikelyAcceptedState state) {
			PeerNode[] all;
			synchronized(this) {
				if(acceptedBy != null) return;
				if(!waitingFor.contains(peer)) return;
				acceptedBy = peer;
				acceptedState = state;
				tag.addRoutedTo(peer, offeredKey);
				notifyAll();
				all = waitingFor.toArray(new PeerNode[waitingFor.size()]);
			}
			if(all.length == 1) return;
			for(PeerNode p : all)
				if(p != peer) p.outputLoadTracker(realTime).unqueueSlotWaiter(this);
		}
		
		/** Some sort of failure.
		 * @param reallyFailed If true, we can't route to the node, or should reconsider 
		 * routing to it, due to e.g. backoff or disconnection. If false, this is 
		 * something like the node is now regarded as low capacity so we should consider
		 * other nodes, but still allow this one.
		 */
		void onFailed(PeerNode peer, boolean reallyFailed) {
			synchronized(this) {
				if(acceptedBy != null) return;
				if(reallyFailed) {
					waitingFor.remove(peer);
					if(!waitingFor.isEmpty()) return;
				}
				failed = true;
			}
		}
	}
	
	/** Uses the information we receive on the load on the target node to determine whether
	 * we can route to it and when we can route to it.
	 */
	class OutputLoadTracker {
		
		final boolean realTime;
		
		private PeerLoadStats lastIncomingLoadStats;
		
		public void reportLoadStatus(PeerLoadStats stat) {
			if(logMINOR) Logger.minor(this, "Got load status : "+stat);
			synchronized(routedToLock) {
				lastIncomingLoadStats = stat;
				maybeNotifySlotWaiter();
			}
		}
		
		public synchronized PeerLoadStats getLastIncomingLoadStats(boolean realTime) {
			return lastIncomingLoadStats;
		}
		
		OutputLoadTracker(boolean realTime) {
			this.realTime = realTime;
		}
		
		public IncomingLoadSummaryStats getIncomingLoadStats() {
			PeerLoadStats loadStats;
			synchronized(routedToLock) {
				if(lastIncomingLoadStats == null) return null;
				loadStats = lastIncomingLoadStats;
			}
			RunningRequestsSnapshot runningRequests = node.nodeStats.getRunningRequestsTo(PeerNode.this, loadStats.averageTransfersOutPerInsert, realTime);
			RunningRequestsSnapshot otherRunningRequests = loadStats.getOtherRunningRequests();
			boolean ignoreLocalVsRemoteBandwidthLiability = node.nodeStats.ignoreLocalVsRemoteBandwidthLiability();
			return new IncomingLoadSummaryStats(runningRequests.totalRequests(), 
					loadStats.outputBandwidthPeerLimit, loadStats.inputBandwidthPeerLimit,
					loadStats.outputBandwidthUpperLimit, loadStats.inputBandwidthUpperLimit,
					runningRequests.calculate(ignoreLocalVsRemoteBandwidthLiability, false),
					runningRequests.calculate(ignoreLocalVsRemoteBandwidthLiability, true),
					otherRunningRequests.calculate(ignoreLocalVsRemoteBandwidthLiability, false),
					otherRunningRequests.calculate(ignoreLocalVsRemoteBandwidthLiability, true));
		}
		
		// FIXME on capacity changing so that we should add another node???
		// FIXME on backoff so that we should add another node???
		
		private final EnumMap<RequestType,LinkedHashSet<SlotWaiter>> slotWaiters = new EnumMap<RequestType,LinkedHashSet<SlotWaiter>>(RequestType.class);
		
		void queueSlotWaiter(SlotWaiter waiter) {
			boolean noLoadStats = false;
			synchronized(routedToLock) {
				noLoadStats = (this.lastIncomingLoadStats == null);
				if(!noLoadStats) {
					makeSlotWaiters(waiter.requestType).add(waiter);
					slotWaiters.get(waiter.requestType).add(waiter);
					return;
				}
			}
			if(logMINOR) Logger.minor(this, "Not waiting for "+this+" as no load stats");
			waiter.onWaited(PeerNode.this, RequestLikelyAcceptedState.UNKNOWN);
		}
		
		private LinkedHashSet<SlotWaiter> makeSlotWaiters(RequestType requestType) {
			LinkedHashSet<SlotWaiter> slots = slotWaiters.get(requestType);
			if(slots == null) {
				slots = new LinkedHashSet<SlotWaiter>();
				slotWaiters.put(requestType, slots);
			}
			return slots;
		}
		
		void unqueueSlotWaiter(SlotWaiter waiter) {
			synchronized(routedToLock) {
				slotWaiters.remove(waiter);
			}
		}
		
		private void failSlotWaiters(boolean reallyFailed) {
			for(RequestType type : RequestType.values()) {
				LinkedHashSet<SlotWaiter> slots; 
				synchronized(routedToLock) {
					slots = slotWaiters.get(type);
					if(slots == null) continue;
					slotWaiters.remove(type);
				}
				for(SlotWaiter w : slots)
					w.onFailed(PeerNode.this, reallyFailed);
			}
		}
		
		private int slotWaiterTypeCounter = 0;
		
		private void maybeNotifySlotWaiter() {
			// FIXME do nothing for now
			// Will be used later by new load management
		}
		
	}
	
	public void noLongerRoutingTo(UIDTag tag, boolean offeredKey) {
		if(offeredKey && !(tag instanceof RequestTag))
			throw new IllegalArgumentException("Only requests can have offeredKey=true");
		synchronized(routedToLock) {
			if(offeredKey)
				tag.removeFetchingOfferedKeyFrom(this);
			else
				tag.removeRoutingTo(this);
			if(logMINOR) Logger.minor(this, "No longer routing "+tag+" to "+this);
			outputLoadTracker(tag.realTimeFlag).maybeNotifySlotWaiter();
		}
	}
	
	public void postUnlock(UIDTag tag) {
		synchronized(routedToLock) {
			if(logMINOR) Logger.minor(this, "Unlocked "+tag);
			outputLoadTracker(tag.realTimeFlag).maybeNotifySlotWaiter();
		}
	}
	
	SlotWaiter createSlotWaiter(RequestTag tag, RequestType type, boolean offeredKey, boolean realTime) {
		return new SlotWaiter(tag, type, this, offeredKey, realTime);
	}

	public IncomingLoadSummaryStats getIncomingLoadStats(boolean realTime) {
		return outputLoadTracker(realTime).getIncomingLoadStats();
	}

	public LoadSender loadSender(boolean realtime) {
		return realtime ? loadSenderRealTime : loadSenderBulk;
	}
	
	/** A fatal timeout occurred, and we don't know whether the peer is still running the
	 * request we passed in for us. If it is, we cannot reuse that slot. So we need to
	 * query it periodically until it is no longer running it. If we cannot send the query
	 * or if we don't get a response, we disconnect via fatalTimeout() (with no arguments).
	 * @param tag The request which we routed to this peer. It may or may not still be
	 * running.
	 */
	public void fatalTimeout(UIDTag tag, boolean offeredKey) {
		// FIXME implement! For now we just disconnect (no-op).
		// A proper implementation requires new messages.
		noLongerRoutingTo(tag, offeredKey);
		fatalTimeout();
	}
	
	/** After a fatal timeout - that is, a timeout that we reasonably believe originated
	 * on the node rather than downstream - we do not know whether or not the node thinks
	 * the request is still running. Hence load management will get really confused and 
	 * likely start to send requests over and over, which are repeatedly rejected.
	 * 
	 * So we have some alternatives: 
	 * 1) Lock the slot forever (or at least until the node reconnects). So every time a
	 * node times out, it loses a slot, and gradually it becomes completely catatonic.
	 * 2) Wait forever for an acknowledgement of the timeout. This may be worth 
	 * investigating. One problem with this is that the slot would still count towards our
	 * overall load management, which is surely a bad thing, although we could make it 
	 * only count towards this node. Also, if it doesn't arrive in a reasonable time maybe
	 * there has been a severe problem e.g. out of memory, bug etc; in that case, waiting
	 * forever may not be sensible.
	 * 3) Disconnect the node. This makes perfect sense for opennet. For darknet it's a 
	 * bit more problematic.
	 * 4) Turn off routing to the node, possibly for a limited period. This would need to
	 * include the effects of disconnection. It might open up some cheapish local DoS's.
	 * 
	 * For all nodes, at present, we disconnect. For darknet nodes, we log an error, and 
	 * allow them to reconnect. */
	public abstract void fatalTimeout();
	
	public abstract boolean shallWeRouteAccordingToOurPeersLocation();
	
	public PeerMessageQueue getMessageQueue() {
		return messageQueue;
	}

	public boolean handleReceivedPacket(byte[] buf, int offset, int length, long now, Peer replyTo) {
		PacketFormat pf;
		synchronized(this) {
			pf = packetFormat;
			if(pf == null) return false;
		}
		return pf.handleReceivedPacket(buf, offset, length, now, replyTo);
	}

	public void checkForLostPackets() {
		PacketFormat pf;
		synchronized(this) {
			pf = packetFormat;
			if(pf == null) return;
		}
		pf.checkForLostPackets();
	}

	public long timeCheckForLostPackets() {
		PacketFormat pf;
		synchronized(this) {
			pf = packetFormat;
			if(pf == null) return Long.MAX_VALUE;
		}
		return pf.timeCheckForLostPackets();
	}

	/** Only called for new format connections, for which we don't care about PacketTracker */
	public void dumpTracker(SessionKey brokenKey) {
		synchronized(this) {
			if(currentTracker == brokenKey) {
				currentTracker = null;
				isConnected = false;
			} else if(previousTracker == brokenKey)
				previousTracker = null;
			else if(unverifiedTracker == brokenKey)
				unverifiedTracker = null;
		}
		// Update connected vs not connected status.
		isConnected();
		setPeerNodeStatus(System.currentTimeMillis());
	}
	
	public void handleMessage(Message m) {
		node.usm.checkFilters(m, crypto.socket);
	}

	public void sendEncryptedPacket(byte[] data) throws LocalAddressException {
		crypto.socket.sendPacket(data, getPeer(), allowLocalAddresses());
	}
	
	public int getMaxPacketSize() {
		return crypto.socket.getMaxPacketSize();
	}
	
	public boolean shouldPadDataPackets() {
		return crypto.config.paddDataPackets();
	}
	
	public void sentThrottledBytes(int count) {
		node.outputThrottle.forceGrab(count);
	}
	
	public void onNotificationOnlyPacketSent(int length) {
		node.nodeStats.reportNotificationOnlyPacketSent(length);
	}
	
	public void resentBytes(int length) {
		resendByteCounter.sentBytes(length);
	}
	
	// FIXME move this to PacketFormat eventually.
	public Random paddingGen() {
		return paddingGen;
	}

	public synchronized boolean matchesPeerAndPort(Peer peer) {
		if(detectedPeer != null && detectedPeer.laxEquals(peer)) return true;
		if(nominalPeer != null) { // FIXME condition necessary???
			for(Peer p : nominalPeer) {
				if(p != null && p.laxEquals(peer)) return true;
			}
		}
		return false;
	}

	public synchronized boolean matchesIP(FreenetInetAddress addr) {
		if(detectedPeer != null && detectedPeer.getFreenetAddress().laxEquals(addr)) return true;
		if(nominalPeer != null) { // FIXME condition necessary???
			for(Peer p : nominalPeer) {
				if(p != null && p.getFreenetAddress().laxEquals(addr)) return true;
			}
		}
		return false;
	}
	
	public MessageItem makeLoadStats(boolean realtime, boolean boostPriority, boolean noRemember) {
		Message msg = loadSender(realtime).makeLoadStats(System.currentTimeMillis(), node.nodeStats.outwardTransfersPerInsert(), noRemember);
		if(msg == null) return null;
		return new MessageItem(msg, null, node.nodeStats.allocationNoticesCounter, boostPriority ? DMT.PRIORITY_NOW : (short)-1);
	}

	public boolean grabSendLoadStatsASAP(boolean realtime) {
		return loadSender(realtime).grabSendASAP();
	}

	public void setSendLoadStatsASAP(boolean realtime) {
		loadSender(realtime).setSendASAP();
	}

	public boolean isOldFNP() {
		synchronized(this) {
			if(packetFormat == null) return false;
			return packetFormat instanceof FNPWrapper;
		}
	}
	
	public DecodingMessageGroup startProcessingDecryptedMessages(int size) {
		return new MyDecodingMessageGroup(size);
	}
	
	class MyDecodingMessageGroup implements DecodingMessageGroup {

		private final ArrayList<Message> messages;
		private final ArrayList<Message> messagesWantSomething;
		
		public MyDecodingMessageGroup(int size) {
			messages = new ArrayList<Message>(size);
			messagesWantSomething = new ArrayList<Message>(size);
		}

		public void processDecryptedMessage(byte[] data, int offset,
				int length, int overhead) {
			Message m = node.usm.decodeSingleMessage(data, offset, length, PeerNode.this, overhead);
			if(m == null) {
				if(logMINOR) Logger.minor(this, "Message not decoded from "+PeerNode.this+" ("+PeerNode.this.getVersionNumber()+")");
				return;
			}
			if(DMT.isPeerLoadStatusMessage(m)) {
				handleMessage(m);
				return;
			}
			if(DMT.isLoadLimitedRequest(m)) {
				messagesWantSomething.add(m);
			} else {
				messages.add(m);
			}
		}

		public void complete() {
			for(Message msg : messages) {
				handleMessage(msg);
			}
			for(Message msg : messagesWantSomething) {
				handleMessage(msg);
			}
		}
		
	}

	private long maxPeerPingTime() {
		if(node == null)
			return NodeStats.DEFAULT_MAX_PING_TIME * 2;
		NodeStats stats = node.nodeStats;
		if(node.nodeStats == null)
			return NodeStats.DEFAULT_MAX_PING_TIME * 2;
		else
			return stats.maxPeerPingTime();
	}

	protected boolean sendingUOMMainJar;
	protected boolean sendingUOMExtJar;
	
	public synchronized boolean sendingUOMJar(boolean isExt) {
		if(isExt) {
			if(sendingUOMExtJar) return false;
			sendingUOMExtJar = true;
		} else {
			if(sendingUOMMainJar) return false;
			sendingUOMMainJar = true;
		}
		return true;
	}
	
	public synchronized void finishedSendingUOMJar(boolean isExt) {
		if(isExt)
			sendingUOMExtJar = false;
		else
			sendingUOMMainJar = false;
	}

	/** Get the boot ID for purposes of the other node. This is set to a random number on
	 * startup, but also whenever we disconnected(true,...) i.e. whenever we dump the 
	 * message queues and PacketFormat's. */
	public synchronized long getOutgoingBootID() {
		return this.myBootID;
	}

	private long lastIncomingRekey;
	
	static final long THROTTLE_REKEY = 1000;
	
	public synchronized boolean throttleRekey() {
		long now = System.currentTimeMillis();
		if(now - lastIncomingRekey < THROTTLE_REKEY) {
			Logger.error(this, "Two rekeys initiated by other side within "+THROTTLE_REKEY+"ms");
			return true;
		}
		lastIncomingRekey = now;
		return false;
	}

	public boolean fullPacketQueued() {
		PacketFormat pf;
		synchronized(this) {
			pf = packetFormat;
			if(pf == null) return false;
		}
		return pf.fullPacketQueued(getMaxPacketSize());
	}

	public long timeSendAcks() {
		PacketFormat pf;
		synchronized(this) {
			pf = packetFormat;
			if(pf == null) return Long.MAX_VALUE;
		}
		return pf.timeSendAcks();
	}
	
}
