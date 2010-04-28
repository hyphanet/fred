/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
/* Freenet 0.7 node. */
package freenet.node;

import java.text.DecimalFormat;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.Vector;

import freenet.node.stats.DataStoreInstanceType;
import freenet.node.stats.DataStoreStats;
import freenet.node.stats.NotAvailNodeStoreStats;
import freenet.node.stats.StoreCallbackStats;
import org.spaceroots.mantissa.random.MersenneTwister;
import org.tanukisoftware.wrapper.WrapperManager;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.config.Configuration;
import com.db4o.defragment.AvailableClassFilter;
import com.db4o.defragment.BTreeIDMapping;
import com.db4o.defragment.Defragment;
import com.db4o.defragment.DefragmentConfig;
import com.db4o.diagnostic.ClassHasNoFields;
import com.db4o.diagnostic.Diagnostic;
import com.db4o.diagnostic.DiagnosticBase;
import com.db4o.diagnostic.DiagnosticListener;
import com.db4o.ext.Db4oException;
import com.db4o.io.IoAdapter;
import com.db4o.io.RandomAccessFileAdapter;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentMutableConfig;

import freenet.client.FECQueue;
import freenet.client.FetchContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.SplitFileInserterSegment;
import freenet.clients.http.SecurityLevelsToadlet;
import freenet.clients.http.SimpleToadletServer;
import freenet.config.EnumerableOptionCallback;
import freenet.config.FreenetFilePersistentConfig;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.PersistentConfig;
import freenet.config.SubConfig;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DiffieHellman;
import freenet.crypt.EncryptingIoAdapter;
import freenet.crypt.RandomSource;
import freenet.crypt.Yarrow;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.IOStatisticCollector;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.UdpSocketHandler;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.ClientSSKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.l10n.BaseL10n;
import freenet.l10n.NodeL10n;
import freenet.node.NodeDispatcher.NodeDispatcherCallback;
import freenet.node.OpennetManager.ConnectionType;
import freenet.node.SecurityLevels.FRIENDS_THREAT_LEVEL;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.node.fcp.FCPMessage;
import freenet.node.fcp.FeedMessage;
import freenet.node.updater.NodeUpdateManager;
import freenet.node.useralerts.BuildOldAgeUserAlert;
import freenet.node.useralerts.ExtOldAgeUserAlert;
import freenet.node.useralerts.MeaningfulNodeNameUserAlert;
import freenet.node.useralerts.NotEnoughNiceLevelsUserAlert;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.TimeSkewDetectedUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.ForwardPort;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.PluginStore;
import freenet.store.BerkeleyDBFreenetStore;
import freenet.store.CHKStore;
import freenet.store.FreenetStore;
import freenet.store.KeyCollisionException;
import freenet.store.NullFreenetStore;
import freenet.store.PubkeyStore;
import freenet.store.RAMFreenetStore;
import freenet.store.SSKStore;
import freenet.store.SlashdotStore;
import freenet.store.StorableBlock;
import freenet.store.StoreCallback;
import freenet.store.BlockMetadata;
import freenet.store.FreenetStore.StoreType;
import freenet.store.saltedhash.SaltedHashFreenetStore;
import freenet.support.Executor;
import freenet.support.Fields;
import freenet.support.FileLoggerHook;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.LRUQueue;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.PooledExecutor;
import freenet.support.ShortBuffer;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.TokenBucket;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.ShortCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread;
import freenet.support.transport.ip.HostnameSyntaxException;

import static freenet.node.stats.DataStoreKeyType.CHK;
import static freenet.node.stats.DataStoreKeyType.PUB_KEY;
import static freenet.node.stats.DataStoreKeyType.SSK;
import static freenet.node.stats.DataStoreType.*;

/**
 * @author amphibian
 */
public class Node implements TimeSkewDetectorCallback {

	public class MigrateOldStoreData implements Runnable {

		private final boolean clientCache;

		public MigrateOldStoreData(boolean clientCache) {
			this.clientCache = clientCache;
			if(clientCache) {
				oldCHKClientCache = chkClientcache;
				oldPKClientCache = pubKeyClientcache;
				oldSSKClientCache = sskClientcache;
			} else {
				oldCHK = chkDatastore;
				oldPK = pubKeyDatastore;
				oldSSK = sskDatastore;
				oldCHKCache = chkDatastore;
				oldPKCache = pubKeyDatastore;
				oldSSKCache = sskDatastore;
			}
		}

		public void run() {
			System.err.println("Migrating old "+(clientCache ? "client cache" : "datastore"));
			if(clientCache) {
				migrateOldStore(oldCHKClientCache, chkClientcache, true);
				StoreCallback<? extends StorableBlock> old;
				synchronized(Node.this) {
					old = oldCHKClientCache;
					oldCHKClientCache = null;
				}
				closeOldStore(old);
				migrateOldStore(oldPKClientCache, pubKeyClientcache, true);
				synchronized(Node.this) {
					old = oldPKClientCache;
					oldPKClientCache = null;
				}
				closeOldStore(old);
				migrateOldStore(oldSSKClientCache, sskClientcache, true);
				synchronized(Node.this) {
					old = oldSSKClientCache;
					oldSSKClientCache = null;
				}
				closeOldStore(old);
			} else {
				migrateOldStore(oldCHK, chkDatastore, false);
				oldCHK = null;
				migrateOldStore(oldPK, pubKeyDatastore, false);
				oldPK = null;
				migrateOldStore(oldSSK, sskDatastore, false);
				oldSSK = null;
				migrateOldStore(oldCHKCache, chkDatacache, false);
				oldCHKCache = null;
				migrateOldStore(oldPKCache, pubKeyDatacache, false);
				oldPKCache = null;
				migrateOldStore(oldSSKCache, sskDatacache, false);
				oldSSKCache = null;
			}
			System.err.println("Finished migrating old "+(clientCache ? "client cache" : "datastore"));
		}

	}

	volatile CHKStore oldCHK;
	volatile PubkeyStore oldPK;
	volatile SSKStore oldSSK;

	volatile CHKStore oldCHKCache;
	volatile PubkeyStore oldPKCache;
	volatile SSKStore oldSSKCache;

	volatile CHKStore oldCHKClientCache;
	volatile PubkeyStore oldPKClientCache;
	volatile SSKStore oldSSKClientCache;

	private <T extends StorableBlock> void migrateOldStore(StoreCallback<T> old, StoreCallback<T> newStore, boolean canReadClientCache) {
		FreenetStore<T> store = old.getStore();
		if(store instanceof RAMFreenetStore) {
			RAMFreenetStore<T> ramstore = (RAMFreenetStore<T>)store;
			try {
				ramstore.migrateTo(newStore, canReadClientCache);
			} catch (IOException e) {
				Logger.error(this, "Caught migrating old store: "+e, e);
			}
			ramstore.clear();
		} else if(store instanceof SaltedHashFreenetStore) {
			SaltedHashFreenetStore<T> saltstore = (SaltedHashFreenetStore<T>) store;
			// FIXME
			Logger.error(this, "Migrating from from a saltedhashstore not fully supported yet: will not keep old keys");
		}
	}


	public <T extends StorableBlock> void closeOldStore(StoreCallback<T> old) {
		FreenetStore<T> store = old.getStore();
		if(store instanceof SaltedHashFreenetStore) {
			SaltedHashFreenetStore<T> saltstore = (SaltedHashFreenetStore<T>) store;
			saltstore.close();
			saltstore.destruct();
		}
	}


	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}
	private static MeaningfulNodeNameUserAlert nodeNameUserAlert;
	private static BuildOldAgeUserAlert buildOldAgeUserAlert;
	private static TimeSkewDetectedUserAlert timeSkewDetectedUserAlert;

	public class NodeNameCallback extends StringCallback  {
		NodeNameCallback() {
		}
		@Override
		public String get() {
			String name;
			synchronized(this) {
				name = myName;
			}
			if(name.startsWith("Node id|")|| name.equals("MyFirstFreenetNode")){
				clientCore.alerts.register(nodeNameUserAlert);
			}else{
				clientCore.alerts.unregister(nodeNameUserAlert);
			}
			return name;
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			if(get().equals(val)) return;
			else if(val.length() > 128)
				throw new InvalidConfigValueException("The given node name is too long ("+val+')');
			else if("".equals(val))
				val = "~none~";
			synchronized(this) {
				myName = val;
			}
			// We'll broadcast the new name to our connected darknet peers via a differential node reference
			SimpleFieldSet fs = new SimpleFieldSet(true);
			fs.putSingle("myName", myName);
			peers.locallyBroadcastDiffNodeRef(fs, true, false);
			// We call the callback once again to ensure MeaningfulNodeNameUserAlert
			// has been unregistered ... see #1595
			get();
		}
	}

	private class StoreTypeCallback extends StringCallback implements EnumerableOptionCallback {

		@Override
		public String get() {
			synchronized(Node.this) {
				return storeType;
			}
		}

		@Override
		public void set(String val) throws InvalidConfigValueException, NodeNeedRestartException {
			boolean found = false;
			for (String p : getPossibleValues()) {
				if (p.equals(val)) {
					found = true;
					break;
				}
			}
			if (!found)
				throw new InvalidConfigValueException("Invalid store type");

			String type;
			synchronized(Node.this) {
				type = storeType;
			}
			if(type.equals("ram")) {
				synchronized(this) { // Serialise this part.
					makeStore(val);
				}
			} else {
				synchronized(Node.this) {
					storeType = val;
				}
				throw new NodeNeedRestartException("Store type cannot be changed on the fly");
			}
		}

		public String[] getPossibleValues() {
			return new String[] { "bdb-index", "salt-hash", "ram" };
		}
	}

	private class ClientCacheTypeCallback extends StringCallback implements EnumerableOptionCallback {

		@Override
		public String get() {
			synchronized(Node.this) {
				return clientCacheType;
			}
		}

		@Override
		public void set(String val) throws InvalidConfigValueException, NodeNeedRestartException {
			boolean found = false;
			for (String p : getPossibleValues()) {
				if (p.equals(val)) {
					found = true;
					break;
				}
			}
			if (!found)
				throw new InvalidConfigValueException("Invalid store type");

			String type;
			synchronized(Node.this) {
				type = clientCacheType;
				if(clientCacheAwaitingPassword)
					type = "ram";
			}
				synchronized(this) { // Serialise this part.
					String suffix = getStoreSuffix();
					if (val.equals("salt-hash")) {
						byte[] key;
						synchronized(Node.this) {
							key = cachedClientCacheKey;
							cachedClientCacheKey = null;
						}
						if(key == null) {
							MasterKeys keys = null;
							try {
								if(securityLevels.physicalThreatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM) {
									key = new byte[32];
									random.nextBytes(key);
								} else {
									keys = MasterKeys.read(masterKeysFile, random, "");
									key = keys.clientCacheMasterKey;
									keys.clearAllNotClientCacheKey();
								}
							} catch (MasterKeysWrongPasswordException e1) {
								setClientCacheAwaitingPassword();
								synchronized(Node.this) {
									clientCacheType = val;
								}
								throw new InvalidConfigValueException("You must enter the password");
							} catch (MasterKeysFileSizeException e1) {
								throw new InvalidConfigValueException("Master keys file corrupted (too " + e1.sizeToString() + ")");
							} catch (IOException e1) {
								throw new InvalidConfigValueException("Master keys file cannot be accessed: "+e1);
							}
						}
						try {
							initSaltHashClientCacheFS(suffix, true, key);
						} catch (NodeInitException e) {
							Logger.error(this, "Unable to create new store", e);
							System.err.println("Unable to create new store: "+e);
							e.printStackTrace();
							// FIXME l10n both on the NodeInitException and the wrapper message
							throw new InvalidConfigValueException("Unable to create new store: "+e);
						} finally {
							MasterKeys.clear(key);
						}
					} else if(val.equals("ram")) {
						initRAMClientCacheFS();
					} else /*if(val.equals("none")) */{
						initNoClientCacheFS();
					}

					synchronized(Node.this) {
						clientCacheType = val;
					}
				}
		}

		public String[] getPossibleValues() {
			return new String[] { "salt-hash", "ram", "none" };
		}
	}

	private static class L10nCallback extends StringCallback implements EnumerableOptionCallback {
		@Override
		public String get() {
			return NodeL10n.getBase().getSelectedLanguage().fullName;
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			if(val == null || get().equalsIgnoreCase(val)) return;
			try {
				NodeL10n.getBase().setLanguage(BaseL10n.LANGUAGE.mapToLanguage(val));
			} catch (MissingResourceException e) {
				throw new InvalidConfigValueException(e.getLocalizedMessage());
			}
			PluginManager.setLanguage(NodeL10n.getBase().getSelectedLanguage());
		}

		public String[] getPossibleValues() {
			return BaseL10n.LANGUAGE.valuesWithFullNames();
		}
	}

	private final File dbFile;
	private final File dbFileCrypt;
	private boolean defragDatabaseOnStartup;
	private boolean defragOnce;
	/** db4o database for node and client layer.
	 * Other databases can be created for the datastore (since its usage
	 * patterns and content are completely different), or for plugins (for
	 * security reasons). */
	public ObjectContainer db;
	/** A fixed random number which identifies the top-level objects belonging to
	 * this node, as opposed to any others that might be stored in the same database
	 * (e.g. because of many-nodes-in-one-VM). */
	public long nodeDBHandle;

	private boolean autoChangeDatabaseEncryption = true;

	/** Stats */
	public final NodeStats nodeStats;
	public final NetworkIDManager netid;

	/** Config object for the whole node. */
	public final PersistentConfig config;

	// Static stuff related to logger

	/** Directory to log to */
	static File logDir;
	/** Maximum size of gzipped logfiles */
	static long maxLogSize;
	/** Log config handler */
	public static LoggingConfigHandler logConfigHandler;

	public static final int PACKETS_IN_BLOCK = 32;
	public static final int PACKET_SIZE = 1024;
	public static final double DECREMENT_AT_MIN_PROB = 0.25;
	public static final double DECREMENT_AT_MAX_PROB = 0.5;
	// Send keepalives every 14-28 seconds. Comfortably fits within 30 second timeout.
	// If the packet is dropped, we will send ack requests etc, so this should be fine.
	public static final int KEEPALIVE_INTERVAL = 14000;
	// If no activity for 30 seconds, node is dead
	public static final int MAX_PEER_INACTIVITY = 60000;
	/** Time after which a handshake is assumed to have failed. */
	public static final int HANDSHAKE_TIMEOUT = 4800; // Keep the below within the 30 second assumed timeout.
	// Inter-handshake time must be at least 2x handshake timeout
	public static final int MIN_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT*2; // 10-20 secs
	public static final int RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT*2; // avoid overlap when the two handshakes are at the same time
	public static final int MIN_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*4;
	public static final int RANDOMIZED_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*2; // 20-30 secs
	public static final int MIN_TIME_BETWEEN_VERSION_SENDS = HANDSHAKE_TIMEOUT*4;
	public static final int RANDOMIZED_TIME_BETWEEN_VERSION_SENDS = HANDSHAKE_TIMEOUT*2; // 20-30 secs
	public static final int MIN_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS = HANDSHAKE_TIMEOUT*24; // 2-5 minutes
	public static final int RANDOMIZED_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS = HANDSHAKE_TIMEOUT*36;
	public static final int MIN_BURSTING_HANDSHAKE_BURST_SIZE = 1; // 1-4 handshake sends per burst
	public static final int RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE = 3;
	// If we don't receive any packets at all in this period, from any node, tell the user
	public static final long ALARM_TIME = 60*1000;

	// 900ms
	static final int MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS = 900;
	static final int MIN_INTERVAL_BETWEEN_INCOMING_PROBE_REQUESTS = 1000;
	public static final int SYMMETRIC_KEY_LENGTH = 32; // 256 bits - note that this isn't used everywhere to determine it
	/** Minimum space for zipped logfiles on testnet */
	static final long TESTNET_MIN_MAX_ZIPPED_LOGFILES = 512*1024*1024;
	static final String TESTNET_MIN_MAX_ZIPPED_LOGFILES_STRING = "512M";

	/** Datastore directory */
	private final File storeDir;

	/** Datastore properties */
	private String storeType;
	private int storeBloomFilterSize;
	private final boolean storeBloomFilterCounting;
	private boolean storeSaltHashResizeOnStart;

	/** The number of bytes per key total in all the different datastores. All the datastores
	 * are always the same size in number of keys. */
	static final int sizePerKey = CHKBlock.DATA_LENGTH + CHKBlock.TOTAL_HEADERS_LENGTH +
		DSAPublicKey.PADDED_SIZE + SSKBlock.DATA_LENGTH + SSKBlock.TOTAL_HEADERS_LENGTH;

	/** The maximum number of keys stored in each of the datastores, cache and store combined. */
	private long maxTotalKeys;
	long maxCacheKeys;
	long maxStoreKeys;
	/** The maximum size of the datastore. Kept to avoid rounding turning 5G into 5368698672 */
	private long maxTotalDatastoreSize;
	/** If true, store shrinks occur immediately even if they are over 10% of the store size. If false,
	 * we just set the storeSize and do an offline shrink on the next startup. Online shrinks do not
	 * preserve the most recently used data so are not recommended. */
	private boolean storeForceBigShrinks;

	/* These are private because must be protected by synchronized(this) */
	private Environment storeEnvironment;
	private EnvironmentMutableConfig envMutableConfig;
	private final SemiOrderedShutdownHook shutdownHook;
	private long databaseMaxMemory;
	/** The CHK datastore. Long term storage; data should only be inserted here if
	 * this node is the closest location on the chain so far, and it is on an
	 * insert (because inserts will always reach the most specialized node; if we
	 * allow requests to store here, then we get pollution by inserts for keys not
	 * close to our specialization). These conclusions derived from Oskar's simulations. */
	private CHKStore chkDatastore;
	/** The SSK datastore. See description for chkDatastore. */
	private SSKStore sskDatastore;
	/** The store of DSAPublicKeys (by hash). See description for chkDatastore. */
	private PubkeyStore pubKeyDatastore;

	/** Client cache store type */
	private String clientCacheType;
	/** Client cache could not be opened so is a RAMFS until the correct password is entered */
	private boolean clientCacheAwaitingPassword;
	private boolean databaseAwaitingPassword;
	/** Client cache maximum cached keys for each type */
	long maxClientCacheKeys;
	/** Maximum size of the client cache. Kept to avoid rounding problems. */
	private long maxTotalClientCacheSize;

	/** Cached client cache key if the user is in the first-time wizard */
	private byte[] cachedClientCacheKey;

	/** The CHK datacache. Short term cache which stores everything that passes
	 * through this node. */
	private CHKStore chkDatacache;
	/** The SSK datacache. Short term cache which stores everything that passes
	 * through this node. */
	private SSKStore sskDatacache;
	/** The public key datacache (by hash). Short term cache which stores
	 * everything that passes through this node. */
	private PubkeyStore pubKeyDatacache;

	/** The CHK client cache. Caches local requests only. */
	private CHKStore chkClientcache;
	/** The SSK client cache. Caches local requests only. */
	private SSKStore sskClientcache;
	/** The pubkey client cache. Caches local requests only. */
	private PubkeyStore pubKeyClientcache;

	// These only cache keys for 30 minutes.

	// FIXME make the first two configurable
	private long maxSlashdotCacheSize;
	private int maxSlashdotCacheKeys;
	static final long PURGE_INTERVAL = 60*1000;

	private CHKStore chkSlashdotcache;
	private SlashdotStore<CHKBlock> chkSlashdotcacheStore;
	private SSKStore sskSlashdotcache;
	private SlashdotStore<SSKBlock> sskSlashdotcacheStore;
	private PubkeyStore pubKeySlashdotcache;
	private SlashdotStore<DSAPublicKey> pubKeySlashdotcacheStore;

	/** If false, only ULPRs will use the slashdot cache. If true, everything does. */
	private boolean useSlashdotCache;
	/** If true, we write stuff to the datastore even though we shouldn't because the HTL is
	 * too high. However it is flagged as old so it won't be included in the Bloom filter for
	 * sharing purposes. */
	private boolean writeLocalToDatastore;

	final GetPubkey getPubKey;

	/** RequestSender's currently transferring, by key */
	private final HashMap<NodeCHK, RequestSender> transferringRequestSenders;
	/** UIDs of RequestHandler's currently transferring */
	private final HashSet<Long> transferringRequestHandlers;
	/** FetchContext for ARKs */
	public final FetchContext arkFetcherContext;

	/** IP detector */
	public final NodeIPDetector ipDetector;
	/** For debugging/testing, set this to true to stop the
	 * probabilistic decrement at the edges of the HTLs. */
	boolean disableProbabilisticHTLs;

	/** HashSet of currently running request UIDs */
	private final HashMap<Long,UIDTag> runningUIDs;
	private final HashMap<Long,RequestTag> runningCHKGetUIDs;
	private final HashMap<Long,RequestTag> runningLocalCHKGetUIDs;
	private final HashMap<Long,RequestTag> runningSSKGetUIDs;
	private final HashMap<Long,RequestTag> runningLocalSSKGetUIDs;
	private final HashMap<Long,InsertTag> runningCHKPutUIDs;
	private final HashMap<Long,InsertTag> runningLocalCHKPutUIDs;
	private final HashMap<Long,InsertTag> runningSSKPutUIDs;
	private final HashMap<Long,InsertTag> runningLocalSSKPutUIDs;
	private final HashMap<Long,OfferReplyTag> runningCHKOfferReplyUIDs;
	private final HashMap<Long,OfferReplyTag> runningSSKOfferReplyUIDs;

	/** Semi-unique ID for swap requests. Used to identify us so that the
	 * topology can be reconstructed. */
	public long swapIdentifier;
	private String myName;
	public final LocationManager lm;
	/** My peers */
	public final PeerManager peers;
	/** Directory to put node, peers, etc into */
	final File nodeDir;
	/** File to write crypto master keys into, possibly passworded */
	final File masterKeysFile;
	/** Directory to put extra peer data into */
	final File extraPeerDataDir;
	/** Strong RNG */
	public final RandomSource random;
	/** Weak but fast RNG */
	public final Random fastWeakRandom;
	/** The object which handles incoming messages and allows us to wait for them */
	final MessageCore usm;

	// Darknet stuff

	NodeCrypto darknetCrypto;

	// Opennet stuff

	private final NodeCryptoConfig opennetCryptoConfig;
	OpennetManager opennet;
	private volatile boolean isAllowedToConnectToSeednodes;
	private int maxOpennetPeers;
	private boolean acceptSeedConnections;
	private boolean passOpennetRefsThroughDarknet;

	// General stuff

	public final Executor executor;
	public final PacketSender ps;
	final DNSRequester dnsr;
	final NodeDispatcher dispatcher;
	public final UptimeEstimator uptime;
	final boolean testnetEnabled;
	final TestnetHandler testnetHandler;
	public final TokenBucket outputThrottle;
	public boolean throttleLocalData;
	private int outputBandwidthLimit;
	private int inputBandwidthLimit;
	boolean inputLimitDefault;
	final boolean enableARKs;
	final boolean enablePerNodeFailureTables;
	final boolean enableULPRDataPropagation;
	final boolean enableSwapping;
	private volatile boolean publishOurPeersLocation;
	private volatile boolean routeAccordingToOurPeersLocation;
	boolean enableSwapQueueing;
	boolean enablePacketCoalescing;
	public static final short DEFAULT_MAX_HTL = (short)18;
	private short maxHTL;
	/** Should inserts fork when the HTL reaches cacheability? */
	public static boolean FORK_ON_CACHEABLE_DEFAULT = true;
	public final IOStatisticCollector collector;
	/** Type identifier for fproxy node to node messages, as sent on DMT.nodeToNodeMessage's */
	public static final int N2N_MESSAGE_TYPE_FPROXY = 1;
	/** Type identifier for differential node reference messages, as sent on DMT.nodeToNodeMessage's */
	public static final int N2N_MESSAGE_TYPE_DIFFNODEREF = 2;
	/** Identifier within fproxy messages for simple, short text messages to be displayed on the homepage as useralerts */
	public static final int N2N_TEXT_MESSAGE_TYPE_USERALERT = 1;
	/** Identifier within fproxy messages for an offer to transfer a file */
	public static final int N2N_TEXT_MESSAGE_TYPE_FILE_OFFER = 2;
	/** Identifier within fproxy messages for accepting an offer to transfer a file */
	public static final int N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_ACCEPTED = 3;
	/** Identifier within fproxy messages for rejecting an offer to transfer a file */
	public static final int N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_REJECTED = 4;
	/** Identified within friend feed for the recommendation of a bookmark */
	public static final int N2N_TEXT_MESSAGE_TYPE_BOOKMARK = 5;
	/** Identified within friend feed for the recommendation of a file */
	public static final int N2N_TEXT_MESSAGE_TYPE_DOWNLOAD = 6;
	public static final int EXTRA_PEER_DATA_TYPE_N2NTM = 1;
	public static final int EXTRA_PEER_DATA_TYPE_PEER_NOTE = 2;
	public static final int EXTRA_PEER_DATA_TYPE_QUEUED_TO_SEND_N2NM = 3;
	public static final int EXTRA_PEER_DATA_TYPE_BOOKMARK = 4;
	public static final int EXTRA_PEER_DATA_TYPE_DOWNLOAD = 5;
	public static final int PEER_NOTE_TYPE_PRIVATE_DARKNET_COMMENT = 1;

	/** The bootID of the last time the node booted up. Or -1 if we don't know due to
	 * permissions problems, or we suspect that the node has been booted and not
	 * written the file e.g. if we can't write it. So if we want to compare data
	 * gathered in the last session and only recorded to disk on a clean shutdown
	 * to data we have now, we just include the lastBootID. */
	public final long lastBootID;
	public final long bootID;
	public final long startupTime;

	private SimpleToadletServer toadlets;

	public final NodeClientCore clientCore;

	// ULPRs, RecentlyFailed, per node failure tables, are all managed by FailureTable.
	final FailureTable failureTable;

	// The version we were before we restarted.
	public int lastVersion;

	/** NodeUpdater **/
	public final NodeUpdateManager nodeUpdater;

	public final SecurityLevels securityLevels;

	// Things that's needed to keep track of
	public final PluginManager pluginManager;

	// Helpers
	public final InetAddress localhostAddress;
	public final FreenetInetAddress fLocalhostAddress;

	private boolean wasTestnet;

	// The node starter
	private static NodeStarter nodeStarter;

	// The watchdog will be silenced until it's true
	private boolean hasStarted;
	private boolean isStopping = false;

	/**
	 * Minimum uptime for us to consider a node an acceptable place to store a key. We store a key
	 * to the datastore only if it's from an insert, and we are a sink, but when calculating whether
	 * we are a sink we ignore nodes which have less uptime (percentage) than this parameter.
	 */
	static final int MIN_UPTIME_STORE_KEY = 40;

	private volatile boolean isPRNGReady = false;

	private boolean storePreallocate;

	/**
	 * Read all storable settings (identity etc) from the node file.
	 * @param filename The name of the file to read from.
	 * @throws IOException throw when I/O error occur
	 */
	private void readNodeFile(String filename) throws IOException {
		// REDFLAG: Any way to share this code with NodePeer?
		FileInputStream fis = new FileInputStream(filename);
		InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
		BufferedReader br = new BufferedReader(isr);
		SimpleFieldSet fs = new SimpleFieldSet(br, false, true);
		br.close();
		// Read contents
		String[] udp = fs.getAll("physical.udp");
		if((udp != null) && (udp.length > 0)) {
			for(String udpAddr : udp) {
				// Just keep the first one with the correct port number.
				Peer p;
				try {
					p = new Peer(udpAddr, false, true);
				} catch (HostnameSyntaxException e) {
					Logger.error(this, "Invalid hostname or IP Address syntax error while parsing our darknet node reference: "+udpAddr);
					System.err.println("Invalid hostname or IP Address syntax error while parsing our darknet node reference: "+udpAddr);
					continue;
				} catch (PeerParseException e) {
					throw (IOException)new IOException().initCause(e);
				}
				if(p.getPort() == getDarknetPortNumber()) {
					// DNSRequester doesn't deal with our own node
					ipDetector.setOldIPAddress(p.getFreenetAddress());
					break;
				}
			}
		}

		darknetCrypto.readCrypto(fs);

		swapIdentifier = Fields.bytesToLong(darknetCrypto.identityHashHash);
		String loc = fs.get("location");
		double locD = Location.getLocation(loc);
		if (locD == -1.0)
			throw new IOException("Invalid location: " + loc);
		lm.setLocation(locD);
		myName = fs.get("myName");
		if(myName == null) {
			myName = newName();
		}

		String verString = fs.get("version");
		if(verString == null) {
			Logger.error(this, "No version!");
			System.err.println("No version!");
		} else {
			lastVersion = Version.getArbitraryBuildNumber(verString, -1);
		}

		wasTestnet = Fields.stringToBool(fs.get("testnet"), false);
	}

	public void makeStore(String val) throws InvalidConfigValueException {
		String suffix = getStoreSuffix();
		if (val.equals("salt-hash")) {
			try {
				initSaltHashFS(suffix, true, null);
			} catch (NodeInitException e) {
				Logger.error(this, "Unable to create new store", e);
				System.err.println("Unable to create new store: "+e);
				e.printStackTrace();
				// FIXME l10n both on the NodeInitException and the wrapper message
				throw new InvalidConfigValueException("Unable to create new store: "+e);
			}
		} else if (val.equals("bdb-index")) {
			try {
				initBDBFS(suffix);
			} catch (NodeInitException e) {
				Logger.error(this, "Unable to create new store", e);
				System.err.println("Unable to create new store: "+e);
				e.printStackTrace();
				// FIXME l10n both on the NodeInitException and the wrapper message
				throw new InvalidConfigValueException("Unable to create new store: "+e);
			}
		} else {
			initRAMFS();
		}

		synchronized(Node.this) {
			storeType = val;
		}
	}


	private String newName() {
		return "Node id|"+random.nextLong();
	}

	private final Object writeNodeFileSync = new Object();

	public void writeNodeFile() {
		synchronized(writeNodeFileSync) {
			writeNodeFile(new File(nodeDir, "node-"+getDarknetPortNumber()), new File(nodeDir, "node-"+getDarknetPortNumber()+".bak"));
		}
	}

	public void writeOpennetFile() {
		OpennetManager om = opennet;
		if(om != null) om.writeFile();
	}

	private void writeNodeFile(File orig, File backup) {
		SimpleFieldSet fs = darknetCrypto.exportPrivateFieldSet();

		if(orig.exists()) backup.delete();

		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(backup);
			fs.writeTo(fos);
			FileUtil.renameTo(backup, orig);
		} catch (IOException ioe) {
			Logger.error(this, "IOE :"+ioe.getMessage(), ioe);
			return;
		} finally {
			Closer.close(fos);
		}
	}

	private void initNodeFileSettings() {
		Logger.normal(this, "Creating new node file from scratch");
		// Don't need to set getDarknetPortNumber()
		// FIXME use a real IP!
		darknetCrypto.initCrypto();
		swapIdentifier = Fields.bytesToLong(darknetCrypto.identityHashHash);
		myName = newName();
	}

	/**
	 * Read the config file from the arguments.
	 * Then create a node.
	 * Anything that needs static init should ideally be in here.
	 * @param args
	 */
	public static void main(String[] args) throws IOException {
		NodeStarter.main(args);
	}

	public boolean isUsingWrapper(){
		if(nodeStarter!=null && WrapperManager.isControlledByNativeWrapper())
			return true;
		else
			return false;
	}

	public NodeStarter getNodeStarter(){
		return nodeStarter;
	}

	/**
	 * Create a Node from a Config object.
	 * @param config The Config object for this node.
	 * @param r The random number generator for this node. Passed in because we may want
	 * to use a non-secure RNG for e.g. one-JVM live-code simulations. Should be a Yarrow in
	 * a production node. Yarrow will be used if that parameter is null
	 * @param weakRandom The fast random number generator the node will use. If null a MT
	 * instance will be used, seeded from the secure PRNG.
	 * @param lc logging config Handler
	 * @param ns NodeStarter
	 * @param executor Executor
	 * @throws NodeInitException If the node initialization fails.
	 */
	 Node(PersistentConfig config, RandomSource r, RandomSource weakRandom, LoggingConfigHandler lc, NodeStarter ns, Executor executor) throws NodeInitException {
		// Easy stuff
		String tmp = "Initializing Node using Freenet Build #"+Version.buildNumber()+" r"+Version.cvsRevision()+" and freenet-ext Build #"+NodeStarter.extBuildNumber+" r"+NodeStarter.extRevisionNumber+" with "+System.getProperty("java.vendor")+" JVM version "+System.getProperty("java.version")+" running on "+System.getProperty("os.arch")+' '+System.getProperty("os.name")+' '+System.getProperty("os.version");
		Logger.normal(this, tmp);
		System.out.println(tmp);
		collector = new IOStatisticCollector();
		this.executor = executor;
		nodeStarter=ns;
		if(logConfigHandler != lc)
			logConfigHandler=lc;
		getPubKey = new GetPubkey(this);
		startupTime = System.currentTimeMillis();
		SimpleFieldSet oldConfig = config.getSimpleFieldSet();
		// Setup node-specific configuration
		final SubConfig nodeConfig = new SubConfig("node", config);

		int sortOrder = 0;

		// l10n stuffs
		nodeConfig.register("l10n", Locale.getDefault().getLanguage().toLowerCase(), sortOrder++, false, true,
				"Node.l10nLanguage",
				"Node.l10nLanguageLong",
				new L10nCallback());

		try {
			new NodeL10n(BaseL10n.LANGUAGE.mapToLanguage(nodeConfig.getString("l10n")));
		} catch (MissingResourceException e) {
			try {
				new NodeL10n(BaseL10n.LANGUAGE.mapToLanguage(nodeConfig.getOption("l10n").getDefault()));
			} catch (MissingResourceException e1) {
				new NodeL10n(BaseL10n.LANGUAGE.mapToLanguage(BaseL10n.LANGUAGE.getDefault().shortCode));
			}
		}

		// FProxy config needs to be here too
		SubConfig fproxyConfig = new SubConfig("fproxy", config);
		try {
			toadlets = new SimpleToadletServer(fproxyConfig, new ArrayBucketFactory(), executor, this);
			fproxyConfig.finishedInitialization();
			toadlets.start();
		} catch (IOException e4) {
			Logger.error(this, "Could not start web interface: "+e4, e4);
			System.err.println("Could not start web interface: "+e4);
			e4.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_FPROXY, "Could not start FProxy: "+e4);
		} catch (InvalidConfigValueException e4) {
			System.err.println("Invalid config value, cannot start web interface: "+e4);
			e4.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_FPROXY, "Could not start FProxy: "+e4);
		}

		// Setup RNG if needed : DO NOT USE IT BEFORE THAT POINT!
		if(r == null) {
			final NativeThread entropyGatheringThread = new NativeThread(new Runnable() {

				private void recurse(File f) {
					if(isPRNGReady)
						return;
					File[] subDirs = f.listFiles(new FileFilter() {

						public boolean accept(File pathname) {
							return pathname.exists() && pathname.canRead() && pathname.isDirectory();
						}
					});


					// @see http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5086412
					if(subDirs != null)
						for(File currentDir : subDirs)
							recurse(currentDir);
				}

				public void run() {
					for(File root : File.listRoots()) {
						if(isPRNGReady)
							return;
						recurse(root);
					}
				}
			}, "Entropy Gathering Thread", NativeThread.MIN_PRIORITY, true);

			entropyGatheringThread.start();
			this.random = new Yarrow();
			DiffieHellman.init(random);

		} else // if it's not null it's because we are running in the simulator
			this.random = r;
		isPRNGReady = true;
		toadlets.getStartupToadlet().setIsPRNGReady();
		if(weakRandom == null) {
			byte buffer[] = new byte[16];
			random.nextBytes(buffer);
			this.fastWeakRandom = new MersenneTwister(buffer);
		}else
			this.fastWeakRandom = weakRandom;

		nodeNameUserAlert = new MeaningfulNodeNameUserAlert(this);
		recentlyCompletedIDs = new LRUQueue<Long>();
		this.config = config;
		lm = new LocationManager(random, this);

		try {
			localhostAddress = InetAddress.getByName("127.0.0.1");
		} catch (UnknownHostException e3) {
			// Does not do a reverse lookup, so this is impossible
			throw new Error(e3);
		}
		fLocalhostAddress = new FreenetInetAddress(localhostAddress);
		transferringRequestSenders = new HashMap<NodeCHK, RequestSender>();
		transferringRequestHandlers = new HashSet<Long>();
		runningUIDs = new HashMap<Long,UIDTag>();
		runningCHKGetUIDs = new HashMap<Long,RequestTag>();
		runningLocalCHKGetUIDs = new HashMap<Long,RequestTag>();
		runningSSKGetUIDs = new HashMap<Long,RequestTag>();
		runningLocalSSKGetUIDs = new HashMap<Long,RequestTag>();
		runningCHKPutUIDs = new HashMap<Long,InsertTag>();
		runningLocalCHKPutUIDs = new HashMap<Long,InsertTag>();
		runningSSKPutUIDs = new HashMap<Long,InsertTag>();
		runningLocalSSKPutUIDs = new HashMap<Long,InsertTag>();
		runningCHKOfferReplyUIDs = new HashMap<Long,OfferReplyTag>();
		runningSSKOfferReplyUIDs = new HashMap<Long,OfferReplyTag>();

		this.securityLevels = new SecurityLevels(this, config);

		// Directory for node-related files other than store

		nodeConfig.register("nodeDir", ".", sortOrder++, true, true /* because can't be changed on the fly, also for packages */, "Node.nodeDir", "Node.nodeDirLong",
				new StringCallback() {
					@Override
					public String get() {
						return nodeDir.getPath();
					}
					@Override
					public void set(String val) throws InvalidConfigValueException {
						if(nodeDir.equals(new File(val))) return;
						// FIXME support it
						// Don't translate the below as very few users will use it.
						throw new InvalidConfigValueException("Moving node directory on the fly not supported at present");
					}
					@Override
					public boolean isReadOnly() {
				        return true;
			        }
		});

		nodeDir = new File(nodeConfig.getString("nodeDir"));
		if(!((nodeDir.exists() && nodeDir.isDirectory()) || (nodeDir.mkdir()))) {
			String msg = "Could not find or create datastore directory";
			throw new NodeInitException(NodeInitException.EXIT_BAD_NODE_DIR, msg);
		}

		nodeConfig.register("autoChangeDatabaseEncryption", true, sortOrder++, true, false, "Node.autoChangeDatabaseEncryption", "Node.autoChangeDatabaseEncryptionLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				synchronized(Node.this) {
					return autoChangeDatabaseEncryption;
				}
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				synchronized(Node.this) {
					autoChangeDatabaseEncryption = val;
				}
			}

		});

		autoChangeDatabaseEncryption = nodeConfig.getBoolean("autoChangeDatabaseEncryption");

		// Location of master key

		nodeConfig.register("masterKeyFile", "master.keys", sortOrder++, true, true, "Node.masterKeyFile", "Node.masterKeyFileLong",
				new StringCallback() {

					@Override
					public String get() {
						if(masterKeysFile == null) return "none";
						else return masterKeysFile.getPath();
					}

					@Override
					public void set(String val) throws InvalidConfigValueException, NodeNeedRestartException {
						// FIXME l10n
						// FIXME wipe the old one and move
						throw new InvalidConfigValueException("Node.masterKeyFile cannot be changed on the fly, you must shutdown, wipe the old file and reconfigure");
					}

		});

		String value = nodeConfig.getString("masterKeyFile");
		File f;
		if(value.equalsIgnoreCase("none")) {
			f = null;
		} else {
			f = new File(value);
			if((!nodeDir.getPath().equals(".")) && !f.isAbsolute() && !value.startsWith(nodeDir.getPath()))
				f = new File(nodeDir, value);

			if(f.exists() && !(f.canWrite() && f.canRead()))
				throw new NodeInitException(NodeInitException.EXIT_CANT_WRITE_MASTER_KEYS, "Cannot read from and write to master keys file "+f);
		}
		masterKeysFile = f;

		// init shutdown hook
		shutdownHook = new SemiOrderedShutdownHook();
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		shutdownHook.addEarlyJob(new NativeThread("Shutdown database", NativeThread.HIGH_PRIORITY, true) {

			public void realRun() {
				System.err.println("Stopping database jobs...");
				if(clientCore == null) return;
				clientCore.killDatabase();
			}

		});

		shutdownHook.addLateJob(new NativeThread("Close database", NativeThread.HIGH_PRIORITY, true) {

			@Override
			public void realRun() {
				if(db == null) return;
				System.err.println("Rolling back unfinished transactions...");
				db.rollback();
				System.err.println("Closing database...");
				db.close();
				if(securityLevels.getPhysicalThreatLevel() == PHYSICAL_THREAT_LEVEL.MAXIMUM) {
					try {
						FileUtil.secureDelete(dbFileCrypt, random);
					} catch (IOException e) {
						// Ignore
					} finally {
						dbFileCrypt.delete();
					}
				}
			}

		});

		nodeConfig.register("defragDatabaseOnStartup", true, sortOrder++, false, true, "Node.defragDatabaseOnStartup", "Node.defragDatabaseOnStartupLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				synchronized(Node.this) {
					return defragDatabaseOnStartup;
				}
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				synchronized(Node.this) {
					defragDatabaseOnStartup = val;
				}
			}

		});

		defragDatabaseOnStartup = nodeConfig.getBoolean("defragDatabaseOnStartup");

		nodeConfig.register("defragOnce", false, sortOrder++, false, true, "Node.defragOnce", "Node.defragOnceLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				synchronized(Node.this) {
					return defragOnce;
				}
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				synchronized(Node.this) {
					defragOnce = val;
				}
			}

		});

		defragOnce = nodeConfig.getBoolean("defragOnce");

		dbFile = new File(nodeDir, "node.db4o");
		dbFileCrypt = new File(nodeDir, "node.db4o.crypt");

		boolean dontCreate = (!dbFile.exists()) && (!dbFileCrypt.exists()) && (!toadlets.fproxyHasCompletedWizard());

		if(!dontCreate) {
			try {
				setupDatabase(null);
			} catch (MasterKeysWrongPasswordException e2) {
				System.out.println("Client database node.db4o is encrypted!");
				databaseAwaitingPassword = true;
			} catch (MasterKeysFileSizeException e2) {
				System.err.println("Unable to decrypt database: master.keys file too " + e2.sizeToString() + "!");
			} catch (IOException e2) {
				System.err.println("Unable to access master.keys file to decrypt database: "+e2);
				e2.printStackTrace();
			}
		} else
			System.out.println("Not creating node.db4o for now, waiting for config as to security level...");

		// Boot ID
		bootID = random.nextLong();
		// Fixed length file containing boot ID. Accessed with random access file. So hopefully it will always be
		// written. Note that we set lastBootID to -1 if we can't _write_ our ID as well as if we can't read it,
		// because if we can't write it then we probably couldn't write it on the last bootup either.
		File bootIDFile = new File(nodeDir, "bootID");
		int BOOT_FILE_LENGTH = 64 / 4; // A long in padded hex bytes
		long oldBootID = -1;
		RandomAccessFile raf = null;
		try {
			raf = new RandomAccessFile(bootIDFile, "rw");
			if(raf.length() < BOOT_FILE_LENGTH) {
				oldBootID = -1;
			} else {
				byte[] buf = new byte[BOOT_FILE_LENGTH];
				raf.readFully(buf);
				String s = new String(buf, "ISO-8859-1");
				try {
					oldBootID = Fields.bytesToLong(HexUtil.hexToBytes(s));
				} catch (NumberFormatException e) {
					oldBootID = -1;
				}
				raf.seek(0);
			}
			String s = HexUtil.bytesToHex(Fields.longToBytes(bootID));
			byte[] buf = s.getBytes("ISO-8859-1");
			if(buf.length != BOOT_FILE_LENGTH)
				System.err.println("Not 16 bytes for boot ID "+bootID+" - WTF??");
			raf.write(buf);
		} catch (IOException e) {
			oldBootID = -1;
			// If we have an error in reading, *or in writing*, we don't reliably know the last boot ID.
		} finally {
			Closer.close(raf);
		}
		lastBootID = oldBootID;

		buildOldAgeUserAlert = new BuildOldAgeUserAlert();

		nodeConfig.register("disableProbabilisticHTLs", false, sortOrder++, true, false, "Node.disablePHTLS", "Node.disablePHTLSLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return disableProbabilisticHTLs;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						disableProbabilisticHTLs = val;
					}

		});

		disableProbabilisticHTLs = nodeConfig.getBoolean("disableProbabilisticHTLs");

		nodeConfig.register("maxHTL", DEFAULT_MAX_HTL, sortOrder++, true, false, "Node.maxHTL", "Node.maxHTLLong", new ShortCallback() {

					@Override
					public Short get() {
						return maxHTL;
					}

					@Override
					public void set(Short val) throws InvalidConfigValueException {
						if(maxHTL < 0) throw new InvalidConfigValueException("Impossible max HTL");
						maxHTL = val;
					}
		}, false);

		maxHTL = nodeConfig.getShort("maxHTL");

		// FIXME maybe these should persist? They need to be private.
		decrementAtMax = random.nextDouble() <= DECREMENT_AT_MAX_PROB;
		decrementAtMin = random.nextDouble() <= DECREMENT_AT_MIN_PROB;

		// Determine where to bind to

		usm = new MessageCore();

		// FIXME maybe these configs should actually be under a node.ip subconfig?
		ipDetector = new NodeIPDetector(this);
		sortOrder = ipDetector.registerConfigs(nodeConfig, sortOrder);

		// ARKs enabled?

		nodeConfig.register("enableARKs", true, sortOrder++, true, false, "Node.enableARKs", "Node.enableARKsLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return enableARKs;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				throw new InvalidConfigValueException("Cannot change on the fly");
			}

			@Override
			public boolean isReadOnly() {
				        return true;
			        }
		});
		enableARKs = nodeConfig.getBoolean("enableARKs");

		nodeConfig.register("enablePerNodeFailureTables", true, sortOrder++, true, false, "Node.enablePerNodeFailureTables", "Node.enablePerNodeFailureTablesLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return enablePerNodeFailureTables;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				throw new InvalidConfigValueException("Cannot change on the fly");
			}

			@Override
			public boolean isReadOnly() {
				        return true;
			      }
		});
		enablePerNodeFailureTables = nodeConfig.getBoolean("enablePerNodeFailureTables");

		nodeConfig.register("enableULPRDataPropagation", true, sortOrder++, true, false, "Node.enableULPRDataPropagation", "Node.enableULPRDataPropagationLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return enableULPRDataPropagation;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				throw new InvalidConfigValueException("Cannot change on the fly");
			}

			@Override
			public boolean isReadOnly() {
				        return true;
			        }
		});
		enableULPRDataPropagation = nodeConfig.getBoolean("enableULPRDataPropagation");

		nodeConfig.register("enableSwapping", true, sortOrder++, true, false, "Node.enableSwapping", "Node.enableSwappingLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return enableSwapping;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				throw new InvalidConfigValueException("Cannot change on the fly");
			}

			@Override
			public boolean isReadOnly() {
				        return true;
			        }
		});
		enableSwapping = nodeConfig.getBoolean("enableSwapping");

		/*
		 * Publish our peers' locations is enabled, even in MAXIMUM network security and/or HIGH friends security,
		 * because a node which doesn't publish its peers' locations will get dramatically less traffic.
		 *
		 * Publishing our peers' locations does make us slightly more vulnerable to some attacks, but I don't think
		 * it's a big difference: swapping reveals the same information, it just doesn't update as quickly. This
		 * may help slightly, but probably not dramatically against a clever attacker.
		 *
		 * FIXME review this decision.
		 */
		nodeConfig.register("publishOurPeersLocation", true, sortOrder++, true, false, "Node.publishOurPeersLocation", "Node.publishOurPeersLocationLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return publishOurPeersLocation;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				publishOurPeersLocation = val;
			}
		});
		publishOurPeersLocation = nodeConfig.getBoolean("publishOurPeersLocation");

		nodeConfig.register("routeAccordingToOurPeersLocation", true, sortOrder++, true, false, "Node.routeAccordingToOurPeersLocation", "Node.routeAccordingToOurPeersLocationLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return routeAccordingToOurPeersLocation;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				routeAccordingToOurPeersLocation = val;
			}
		});
		routeAccordingToOurPeersLocation = nodeConfig.getBoolean("routeAccordingToOurPeersLocation");

		securityLevels.addNetworkThreatLevelListener(new SecurityLevelListener<NETWORK_THREAT_LEVEL>() {

			public void onChange(NETWORK_THREAT_LEVEL oldLevel, NETWORK_THREAT_LEVEL newLevel) {
				synchronized(Node.this) {
					boolean wantFOAF = true;
					if(newLevel == NETWORK_THREAT_LEVEL.MAXIMUM || newLevel == NETWORK_THREAT_LEVEL.HIGH) {
						// Opennet is disabled.
						if(securityLevels.friendsThreatLevel == FRIENDS_THREAT_LEVEL.HIGH)
							wantFOAF = false;
					}
					routeAccordingToOurPeersLocation = wantFOAF;
				}
			}

		});

		securityLevels.addFriendsThreatLevelListener(new SecurityLevelListener<FRIENDS_THREAT_LEVEL>() {

			public void onChange(FRIENDS_THREAT_LEVEL oldLevel, FRIENDS_THREAT_LEVEL newLevel) {
				synchronized(Node.this) {
					boolean wantFOAF = true;
					NETWORK_THREAT_LEVEL networkLevel = securityLevels.networkThreatLevel;
					if(networkLevel == NETWORK_THREAT_LEVEL.MAXIMUM || networkLevel == NETWORK_THREAT_LEVEL.HIGH) {
						// Opennet is disabled.
						if(newLevel == FRIENDS_THREAT_LEVEL.HIGH)
							wantFOAF = false;
					}
					routeAccordingToOurPeersLocation = wantFOAF;
				}
			}

		});

		nodeConfig.register("enableSwapQueueing", true, sortOrder++, true, false, "Node.enableSwapQueueing", "Node.enableSwapQueueingLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return enableSwapQueueing;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				enableSwapQueueing = val;
			}

		});
		enableSwapQueueing = nodeConfig.getBoolean("enableSwapQueueing");

		nodeConfig.register("enablePacketCoalescing", true, sortOrder++, true, false, "Node.enablePacketCoalescing", "Node.enablePacketCoalescingLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return enablePacketCoalescing;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				enablePacketCoalescing = val;
			}

		});
		enablePacketCoalescing = nodeConfig.getBoolean("enablePacketCoalescing");

		// Determine the port number
		// @see #191
		if(oldConfig != null && "-1".equals(oldConfig.get("node.listenPort")))
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_BIND_USM, "Your freenet.ini file is corrupted! 'listenPort=-1'");
		NodeCryptoConfig darknetConfig = new NodeCryptoConfig(nodeConfig, sortOrder++, false, securityLevels);
		sortOrder += NodeCryptoConfig.OPTION_COUNT;

		darknetCrypto = new NodeCrypto(this, false, darknetConfig, startupTime, enableARKs);

		nodeDBHandle = darknetCrypto.getNodeHandle(db);

		if(db != null) {
			db.commit();
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "COMMITTED");
		}

		// Must be created after darknetCrypto
		dnsr = new DNSRequester(this);
		ps = new PacketSender(this);
		if(executor instanceof PooledExecutor)
			((PooledExecutor)executor).setTicker(ps);

		Logger.normal(Node.class, "Creating node...");

		shutdownHook.addEarlyJob(new Thread() {
			@Override
			public void run() {
				if (opennet != null)
					opennet.stop(false);
			}
		});

		shutdownHook.addEarlyJob(new Thread() {
			@Override
			public void run() {
				darknetCrypto.stop();
			}
		});

		// Bandwidth limit

		nodeConfig.register("outputBandwidthLimit", "15K", sortOrder++, false, true, "Node.outBWLimit", "Node.outBWLimitLong", new IntCallback() {
					@Override
					public Integer get() {
						//return BlockTransmitter.getHardBandwidthLimit();
						return outputBandwidthLimit;
					}
					@Override
					public void set(Integer obwLimit) throws InvalidConfigValueException {
						if(obwLimit <= 0) throw new InvalidConfigValueException(l10n("bwlimitMustBePositive"));
						synchronized(Node.this) {
							outputBandwidthLimit = obwLimit;
						}
						outputThrottle.changeNanosAndBucketSize((1000L * 1000L * 1000L) / obwLimit, obwLimit/2);
						nodeStats.setOutputLimit(obwLimit);
					}
		}, true);

		int obwLimit = nodeConfig.getInt("outputBandwidthLimit");
		if(obwLimit <= 0)
			throw new NodeInitException(NodeInitException.EXIT_BAD_BWLIMIT, "Invalid outputBandwidthLimit");
		outputBandwidthLimit = obwLimit;
		// Bucket size of 0.5 seconds' worth of bytes.
		// Add them at a rate determined by the obwLimit.
		// Maximum forced bytes 80%, in other words, 20% of the bandwidth is reserved for
		// block transfers, so we will use that 20% for block transfers even if more than 80% of the limit is used for non-limited data (resends etc).
		int bucketSize = obwLimit/2;
		// Must have at least space for ONE PACKET.
		// FIXME: make compatible with alternate transports.
		bucketSize = Math.max(bucketSize, 2048);
		outputThrottle = new TokenBucket(bucketSize, (1000L*1000L*1000L) / obwLimit, obwLimit/2);

		nodeConfig.register("inputBandwidthLimit", "-1", sortOrder++, false, true, "Node.inBWLimit", "Node.inBWLimitLong",	new IntCallback() {
					@Override
					public Integer get() {
						if(inputLimitDefault) return -1;
						return inputBandwidthLimit;
					}
					@Override
					public void set(Integer ibwLimit) throws InvalidConfigValueException {
						synchronized(Node.this) {
							if(ibwLimit == -1) {
								inputLimitDefault = true;
								ibwLimit = outputBandwidthLimit * 4;
							} else {
								if(ibwLimit <= 1) throw new InvalidConfigValueException(l10n("bandwidthLimitMustBePositiveOrMinusOne"));
								inputLimitDefault = false;
							}
							inputBandwidthLimit = ibwLimit;
						}
						nodeStats.setInputLimit(ibwLimit);
					}
		}, true);

		int ibwLimit = nodeConfig.getInt("inputBandwidthLimit");
		if(ibwLimit == -1) {
			inputLimitDefault = true;
			ibwLimit = obwLimit * 4;
		} else if(ibwLimit <= 0)
			throw new NodeInitException(NodeInitException.EXIT_BAD_BWLIMIT, "Invalid inputBandwidthLimit");
		inputBandwidthLimit = ibwLimit;

		nodeConfig.register("throttleLocalTraffic", false, sortOrder++, true, false, "Node.throttleLocalTraffic", "Node.throttleLocalTrafficLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return throttleLocalData;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				throttleLocalData = val;
			}

		});

		throttleLocalData = nodeConfig.getBoolean("throttleLocalTraffic");

		// Testnet.
		// Cannot be enabled/disabled on the fly.
		// If enabled, forces certain other config options.

		if((testnetHandler = TestnetHandler.maybeCreate(this, config)) != null) {
			String msg = "WARNING: ENABLING TESTNET CODE! This WILL seriously jeopardize your anonymity!";
			Logger.error(this, msg);
			System.err.println(msg);
			testnetEnabled = true;
			if(logConfigHandler.getFileLoggerHook() == null) {
				System.err.println("Forcing logging enabled (essential for testnet)");
				logConfigHandler.forceEnableLogging();
			}
			int x = Logger.globalGetThreshold();
			if(!((x == Logger.MINOR) || (x == Logger.DEBUG))) {
				System.err.println("Forcing log threshold to MINOR for testnet, was "+x);
				Logger.globalSetThreshold(Logger.MINOR);
			}
			if(logConfigHandler.getMaxZippedLogFiles() < TESTNET_MIN_MAX_ZIPPED_LOGFILES) {
				System.err.println("Forcing max zipped logfiles space to 256MB for testnet");
				try {
					logConfigHandler.setMaxZippedLogFiles(TESTNET_MIN_MAX_ZIPPED_LOGFILES_STRING);
				} catch (InvalidConfigValueException e) {
					throw new Error("Impossible: " + e, e);
				} catch (NodeNeedRestartException e) {
					throw new Error("Impossible: " + e, e);
				}
			}
		} else {
			String s = "Testnet mode DISABLED. You may have some level of anonymity. :)\n"+
				"Note that this version of Freenet is still a very early alpha, and may well have numerous bugs and design flaws.\n"+
				"In particular: YOU ARE WIDE OPEN TO YOUR IMMEDIATE PEERS! They can eavesdrop on your requests with relatively little difficulty at present (correlation attacks etc).";
			Logger.normal(this, s);
			System.err.println(s);
			testnetEnabled = false;
			if(wasTestnet) {
				FileLoggerHook flh = logConfigHandler.getFileLoggerHook();
				if(flh != null) flh.deleteAllOldLogFiles();
			}
		}

		File nodeFile = new File(nodeDir, "node-"+getDarknetPortNumber());
		File nodeFileBackup = new File(nodeDir, "node-"+getDarknetPortNumber()+".bak");
		// After we have set up testnet and IP address, load the node file
		try {
			// FIXME should take file directly?
			readNodeFile(nodeFile.getPath());
		} catch (IOException e) {
			try {
				System.err.println("Trying to read node file backup ...");
				readNodeFile(nodeFileBackup.getPath());
			} catch (IOException e1) {
				if(nodeFile.exists() || nodeFileBackup.exists()) {
					System.err.println("No node file or cannot read, (re)initialising crypto etc");
					System.err.println(e1.toString());
					e1.printStackTrace();
					System.err.println("After:");
					System.err.println(e.toString());
					e.printStackTrace();
				} else {
					System.err.println("Creating new cryptographic keys...");
				}
				initNodeFileSettings();
			}
		}

		if(wasTestnet != testnetEnabled) {
			Logger.error(this, "Switched from testnet mode to non-testnet mode or vice versa! Regenerating pubkey, privkey, and deleting logs.");
			// FIXME do we delete logs?
			darknetCrypto.initCrypto();
		}

		usm.setDispatcher(dispatcher=new NodeDispatcher(this));

		// Then read the peers
		peers = new PeerManager(this);
		peers.tryReadPeers(new File(nodeDir, "peers-"+getDarknetPortNumber()).getPath(), darknetCrypto, null, false, false);
		peers.writePeers();
		peers.updatePMUserAlert();

		uptime = new UptimeEstimator(nodeDir, ps, darknetCrypto.identityHash);

		// ULPRs

		failureTable = new FailureTable(this);

		// Opennet

		final SubConfig opennetConfig = new SubConfig("node.opennet", config);
		opennetConfig.register("connectToSeednodes", true, 0, true, false, "Node.withAnnouncement", "Node.withAnnouncementLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return isAllowedToConnectToSeednodes;
			}
			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				if (get().equals(val))
					        return;
				synchronized(Node.this) {
					isAllowedToConnectToSeednodes = val;
					if(opennet != null)
						throw new NodeNeedRestartException(l10n("connectToSeednodesCannotBeChangedMustDisableOpennetOrReboot"));
				}
			}
		});
		isAllowedToConnectToSeednodes = opennetConfig.getBoolean("connectToSeednodes");

		// Can be enabled on the fly
		opennetConfig.register("enabled", false, 0, true, true, "Node.opennetEnabled", "Node.opennetEnabledLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				synchronized(Node.this) {
					return opennet != null;
				}
			}
			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				OpennetManager o;
				synchronized(Node.this) {
					if(val == (opennet != null)) return;
					if(val) {
						try {
							o = opennet = new OpennetManager(Node.this, opennetCryptoConfig, System.currentTimeMillis(), isAllowedToConnectToSeednodes);
						} catch (NodeInitException e) {
							opennet = null;
							throw new InvalidConfigValueException(e.getMessage());
						}
					} else {
						o = opennet;
						opennet = null;
					}
				}
				if(val) o.start();
				else o.stop(true);
				ipDetector.ipDetectorManager.notifyPortChange(getPublicInterfacePorts());
			}
		});
		boolean opennetEnabled = opennetConfig.getBoolean("enabled");

		opennetConfig.register("maxOpennetPeers", OpennetManager.MAX_PEERS_FOR_SCALING, 1, true, false, "Node.maxOpennetPeers",
				"Node.maxOpennetPeersLong", new IntCallback() {
					@Override
					public Integer get() {
						return maxOpennetPeers;
					}
					@Override
					public void set(Integer inputMaxOpennetPeers) throws InvalidConfigValueException {
						if(inputMaxOpennetPeers < 0) throw new InvalidConfigValueException(l10n("mustBePositive"));
						if(inputMaxOpennetPeers > OpennetManager.MAX_PEERS_FOR_SCALING) throw new InvalidConfigValueException(l10n("maxOpennetPeersMustBeTwentyOrLess"));
						maxOpennetPeers = inputMaxOpennetPeers;
						}
					}
		, false);

		maxOpennetPeers = opennetConfig.getInt("maxOpennetPeers");
		if(maxOpennetPeers > OpennetManager.MAX_PEERS_FOR_SCALING) {
			Logger.error(this, "maxOpennetPeers may not be over "+OpennetManager.MAX_PEERS_FOR_SCALING);
			maxOpennetPeers = OpennetManager.MAX_PEERS_FOR_SCALING;
		}

		opennetCryptoConfig = new NodeCryptoConfig(opennetConfig, 2 /* 0 = enabled */, true, securityLevels);

		if(opennetEnabled) {
			opennet = new OpennetManager(this, opennetCryptoConfig, System.currentTimeMillis(), isAllowedToConnectToSeednodes);
			// Will be started later
		} else {
			opennet = null;
		}

		securityLevels.addNetworkThreatLevelListener(new SecurityLevelListener<NETWORK_THREAT_LEVEL>() {

			public void onChange(NETWORK_THREAT_LEVEL oldLevel, NETWORK_THREAT_LEVEL newLevel) {
				if(newLevel == NETWORK_THREAT_LEVEL.HIGH
						|| newLevel == NETWORK_THREAT_LEVEL.MAXIMUM) {
					OpennetManager om;
					synchronized(Node.this) {
						om = opennet;
						if(om != null)
							opennet = null;
					}
					if(om != null) {
						om.stop(true);
						ipDetector.ipDetectorManager.notifyPortChange(getPublicInterfacePorts());
					}
				} else if(newLevel == NETWORK_THREAT_LEVEL.NORMAL
						|| newLevel == NETWORK_THREAT_LEVEL.LOW) {
					OpennetManager o = null;
					synchronized(Node.this) {
						if(opennet == null) {
							try {
								o = opennet = new OpennetManager(Node.this, opennetCryptoConfig, System.currentTimeMillis(), isAllowedToConnectToSeednodes);
							} catch (NodeInitException e) {
								opennet = null;
								Logger.error(this, "UNABLE TO ENABLE OPENNET: "+e, e);
								clientCore.alerts.register(new SimpleUserAlert(false, l10n("enableOpennetFailedTitle"), l10n("enableOpennetFailed", "message", e.getLocalizedMessage()), l10n("enableOpennetFailed", "message", e.getLocalizedMessage()), UserAlert.ERROR));
							}
						}
					}
					if(o != null) {
						o.start();
						ipDetector.ipDetectorManager.notifyPortChange(getPublicInterfacePorts());
					}
				}
				Node.this.config.store();
			}

		});

		opennetConfig.register("acceptSeedConnections", false, 2, true, true, "Node.acceptSeedConnectionsShort", "Node.acceptSeedConnections", new BooleanCallback() {

			@Override
			public Boolean get() {
				return acceptSeedConnections;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				acceptSeedConnections = val;
			}

		});

		acceptSeedConnections = opennetConfig.getBoolean("acceptSeedConnections");

		opennetConfig.finishedInitialization();

		nodeConfig.register("passOpennetPeersThroughDarknet", true, sortOrder++, true, false, "Node.passOpennetPeersThroughDarknet", "Node.passOpennetPeersThroughDarknetLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						synchronized(Node.this) {
							return passOpennetRefsThroughDarknet;
						}
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						synchronized(Node.this) {
							passOpennetRefsThroughDarknet = val;
						}
					}

		});

		passOpennetRefsThroughDarknet = nodeConfig.getBoolean("passOpennetPeersThroughDarknet");

		// Extra Peer Data Directory
		nodeConfig.register("extraPeerDataDir", new File(nodeDir, "extra-peer-data-"+getDarknetPortNumber()).toString(), sortOrder++, true, true /* can't be changed on the fly, also for packages */, "Node.extraPeerDir", "Node.extraPeerDirLong",
				new StringCallback() {
					@Override
					public String get() {
						return extraPeerDataDir.getPath();
					}
					@Override
					public void set(String val) throws InvalidConfigValueException {
						if(extraPeerDataDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving extra peer data directory on the fly not supported at present");
					}
					@Override
					public boolean isReadOnly() {
				        return true;
			        }
		});
		extraPeerDataDir = new File(nodeConfig.getString("extraPeerDataDir"));
		if(!((extraPeerDataDir.exists() && extraPeerDataDir.isDirectory()) || (extraPeerDataDir.mkdir()))) {
			String msg = "Could not find or create extra peer data directory";
			throw new NodeInitException(NodeInitException.EXIT_EXTRA_PEER_DATA_DIR, msg);
		}

		// Name
		nodeConfig.register("name", myName, sortOrder++, false, true, "Node.nodeName", "Node.nodeNameLong",
						new NodeNameCallback());
		myName = nodeConfig.getString("name");

		// Datastore
		nodeConfig.register("storeForceBigShrinks", false, sortOrder++, true, false, "Node.forceBigShrink", "Node.forceBigShrinkLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						synchronized(Node.this) {
							return storeForceBigShrinks;
						}
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						synchronized(Node.this) {
							storeForceBigShrinks = val;
						}
					}

		});

		// Datastore

		nodeConfig.register("storeType", "ram", sortOrder++, true, true, "Node.storeType", "Node.storeTypeLong", new StoreTypeCallback());

		storeType = nodeConfig.getString("storeType");

		/*
		 * Very small initial store size, since the node will preallocate it when starting up for the first time,
		 * BLOCKING STARTUP, and since everyone goes through the wizard anyway...
		 */
		nodeConfig.register("storeSize", "10M", sortOrder++, false, true, "Node.storeSize", "Node.storeSizeLong",
				new LongCallback() {

					@Override
					public Long get() {
						return maxTotalDatastoreSize;
					}

					@Override
					public void set(Long storeSize) throws InvalidConfigValueException {
						if((storeSize < 0) || (storeSize < (32 * 1024 * 1024)))
							throw new InvalidConfigValueException(l10n("invalidStoreSize"));
						long newMaxStoreKeys = storeSize / sizePerKey;
						if(newMaxStoreKeys == maxTotalKeys) return;
						// Update each datastore
						synchronized(Node.this) {
							maxTotalDatastoreSize = storeSize;
							maxTotalKeys = newMaxStoreKeys;
							maxStoreKeys = maxTotalKeys / 2;
							maxCacheKeys = maxTotalKeys - maxStoreKeys;
						}
						try {
							chkDatastore.setMaxKeys(maxStoreKeys, storeForceBigShrinks);
							chkDatacache.setMaxKeys(maxCacheKeys, storeForceBigShrinks);
							pubKeyDatastore.setMaxKeys(maxStoreKeys, storeForceBigShrinks);
							pubKeyDatacache.setMaxKeys(maxCacheKeys, storeForceBigShrinks);
							sskDatastore.setMaxKeys(maxStoreKeys, storeForceBigShrinks);
							sskDatacache.setMaxKeys(maxCacheKeys, storeForceBigShrinks);
						} catch (IOException e) {
							// FIXME we need to be able to tell the user.
							Logger.error(this, "Caught "+e+" resizing the datastore", e);
							System.err.println("Caught "+e+" resizing the datastore");
							e.printStackTrace();
						} catch (DatabaseException e) {
							Logger.error(this, "Caught "+e+" resizing the datastore", e);
							System.err.println("Caught "+e+" resizing the datastore");
							e.printStackTrace();
						}
						//Perhaps a bit hackish...? Seems like this should be near it's definition in NodeStats.
						nodeStats.avgStoreLocation.changeMaxReports((int)maxStoreKeys);
						nodeStats.avgCacheLocation.changeMaxReports((int)maxCacheKeys);
					}
		}, true);

		maxTotalDatastoreSize = nodeConfig.getLong("storeSize");

		if(maxTotalDatastoreSize < 0 || maxTotalDatastoreSize < (32 * 1024 * 1024) && !storeType.equals("ram")) { // totally arbitrary minimum!
			throw new NodeInitException(NodeInitException.EXIT_INVALID_STORE_SIZE, "Invalid store size");
		}

		maxTotalKeys = maxTotalDatastoreSize / sizePerKey;

		nodeConfig.register("storeBloomFilterSize", -1, sortOrder++, true, false, "Node.storeBloomFilterSize",
		        "Node.storeBloomFilterSizeLong", new IntCallback() {
			        private Integer cachedBloomFilterSize;

			        @Override
					public Integer get() {
			        	if (cachedBloomFilterSize == null)
					        cachedBloomFilterSize = storeBloomFilterSize;
				        return cachedBloomFilterSize;
			        }

			        @Override
					public void set(Integer val) throws InvalidConfigValueException, NodeNeedRestartException {
				        cachedBloomFilterSize = val;
				        throw new NodeNeedRestartException("Store bloom filter size cannot be changed on the fly");
			        }

			        @Override
					public boolean isReadOnly() {
				        return !("salt-hash".equals(storeType));
			        }
		        }, true);

		storeBloomFilterSize = nodeConfig.getInt("storeBloomFilterSize");

		nodeConfig.register("storeBloomFilterCounting", true, sortOrder++, true, false,
		        "Node.storeBloomFilterCounting", "Node.storeBloomFilterCountingLong", new BooleanCallback() {
			        private Boolean cachedBloomFilterCounting;

			        @Override
					public Boolean get() {
				        if (cachedBloomFilterCounting == null)
					        cachedBloomFilterCounting = storeBloomFilterCounting;
				        return cachedBloomFilterCounting;
			        }

			        @Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				        cachedBloomFilterCounting = val;
				        throw new NodeNeedRestartException("Store bloom filter type cannot be changed on the fly");
			        }

			        @Override
					public boolean isReadOnly() {
				        return !("salt-hash".equals(storeType));
			        }
		        });

		storeBloomFilterCounting = nodeConfig.getBoolean("storeBloomFilterCounting");

		nodeConfig.register("storeSaltHashResizeOnStart", false, sortOrder++, true, false,
				"Node.storeSaltHashResizeOnStart", "Node.storeSaltHashResizeOnStartLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return storeSaltHashResizeOnStart;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				storeSaltHashResizeOnStart = val;
			}
		});
		storeSaltHashResizeOnStart = nodeConfig.getBoolean("storeSaltHashResizeOnStart");

		nodeConfig.register("storeDir", "datastore", sortOrder++, true, true, "Node.storeDirectory", "Node.storeDirectoryLong",
				new StringCallback() {
					@Override
					public String get() {
						return storeDir.getPath();
					}
					@Override
					public void set(String val) throws InvalidConfigValueException {
						if(storeDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving datastore on the fly not supported at present");
					}
					@Override
					public boolean isReadOnly() {
				        return true;
			        }
		});

		final String suffix = getStoreSuffix();
		String datastoreDir = nodeConfig.getString("storeDir");
		storeDir = new File(datastoreDir);
		if(!((storeDir.exists() && storeDir.isDirectory()) || (storeDir.mkdir()))) {
			String msg = "Could not find or create datastore directory";
			throw new NodeInitException(NodeInitException.EXIT_STORE_OTHER, msg);
		}

		maxStoreKeys = maxTotalKeys / 2;
		maxCacheKeys = maxTotalKeys - maxStoreKeys;

		/*
		 * On Windows, setting the file length normally involves writing lots of zeros.
		 * So it's an uninterruptible system call that takes a loooong time. On OS/X,
		 * presumably the same is true. If the RNG is fast enough, this means that
		 * setting the length and writing random data take exactly the same amount
		 * of time. On most versions of Unix, holes can be created. However on all
		 * systems, predictable disk usage is a good thing. So lets turn it on by
		 * default for now, on all systems. The datastore can be read but mostly not
		 * written while the random data is being written.
		 */
		nodeConfig.register("storePreallocate", true, sortOrder++, true, true, "Node.storePreallocate", "Node.storePreallocateLong",
				new BooleanCallback() {
					@Override
                    public Boolean get() {
	                    return storePreallocate;
                    }

					@Override
                    public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						storePreallocate = val;
						if (storeType.equals("salt-hash")) {
							((SaltedHashFreenetStore<CHKBlock>) chkDatastore.getStore()).setPreallocate(val);
							((SaltedHashFreenetStore<CHKBlock>) chkDatacache.getStore()).setPreallocate(val);
							((SaltedHashFreenetStore<DSAPublicKey>) pubKeyDatastore.getStore()).setPreallocate(val);
							((SaltedHashFreenetStore<DSAPublicKey>) pubKeyDatacache.getStore()).setPreallocate(val);
							((SaltedHashFreenetStore<SSKBlock>) sskDatastore.getStore()).setPreallocate(val);
							((SaltedHashFreenetStore<SSKBlock>) sskDatacache.getStore()).setPreallocate(val);
						}
                    }}
		);
		storePreallocate = nodeConfig.getBoolean("storePreallocate");

		if(File.separatorChar == '/' && System.getProperty("os.name").toLowerCase().indexOf("mac os") < 0) {
			securityLevels.addPhysicalThreatLevelListener(new SecurityLevelListener<SecurityLevels.PHYSICAL_THREAT_LEVEL>() {

				public void onChange(PHYSICAL_THREAT_LEVEL oldLevel, PHYSICAL_THREAT_LEVEL newLevel) {
					try {
						if(newLevel == PHYSICAL_THREAT_LEVEL.LOW)
							nodeConfig.set("storePreallocate", false);
						else
							nodeConfig.set("storePreallocate", true);
					} catch (NodeNeedRestartException e) {
						// Ignore
					} catch (InvalidConfigValueException e) {
						// Ignore
					}
				}
			});
		}

		securityLevels.addPhysicalThreatLevelListener(new SecurityLevelListener<SecurityLevels.PHYSICAL_THREAT_LEVEL>() {

			public void onChange(PHYSICAL_THREAT_LEVEL oldLevel, PHYSICAL_THREAT_LEVEL newLevel) {
					if(newLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM) {
						synchronized(this) {
							clientCacheAwaitingPassword = false;
							databaseAwaitingPassword = false;
						}
						try {
							killMasterKeysFile();
						} catch (IOException e) {
							masterKeysFile.delete();
							Logger.error(this, "Unable to securely delete "+masterKeysFile);
							System.err.println(NodeL10n.getBase().getString("SecurityLevels.cantDeletePasswordFile", "filename", masterKeysFile.getAbsolutePath()));
							clientCore.alerts.register(new SimpleUserAlert(true, NodeL10n.getBase().getString("SecurityLevels.cantDeletePasswordFileTitle"), NodeL10n.getBase().getString("SecurityLevels.cantDeletePasswordFile"), NodeL10n.getBase().getString("SecurityLevels.cantDeletePasswordFileTitle"), UserAlert.CRITICAL_ERROR));
						}
					}
				}

			});

		if(securityLevels.physicalThreatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM) {
			try {
				killMasterKeysFile();
			} catch (IOException e) {
				String msg = "Unable to securely delete old master.keys file when switching to MAXIMUM seclevel!!";
				System.err.println(msg);
				throw new NodeInitException(NodeInitException.EXIT_CANT_WRITE_MASTER_KEYS, msg);
			}
		}

		nodeConfig.register("databaseMaxMemory", "20M", sortOrder++, true, false, "Node.databaseMemory", "Node.databaseMemoryLong",
				new LongCallback() {

			@Override
			public Long get() {
				return databaseMaxMemory;
			}

			@Override
			public void set(Long val) throws InvalidConfigValueException {
				if(val < 0)
					throw new InvalidConfigValueException(l10n("mustBePositive"));
				else {
					long maxHeapMemory = Runtime.getRuntime().maxMemory();
					/* There are some JVMs (for example libgcj 4.1.1) whose Runtime.maxMemory() does not work. */
					if(maxHeapMemory < Long.MAX_VALUE && val > (80 * maxHeapMemory / 100))
						throw new InvalidConfigValueException(l10n("storeMaxMemTooHigh"));
				}

				envMutableConfig.setCacheSize(val);
				try{
					storeEnvironment.setMutableConfig(envMutableConfig);
				} catch (DatabaseException e) {
					throw new InvalidConfigValueException(l10n("errorApplyingConfig", "error", e.getLocalizedMessage()));
				}
				databaseMaxMemory = val;
			}

		}, true);

		/* There are some JVMs (for example libgcj 4.1.1) whose Runtime.maxMemory() does not work. */
		long maxHeapMemory = Runtime.getRuntime().maxMemory();
		databaseMaxMemory = nodeConfig.getLong("databaseMaxMemory");
		// see #1202
		if(maxHeapMemory < Long.MAX_VALUE && databaseMaxMemory > (80 * maxHeapMemory / 100)){
			Logger.error(this, "The databaseMemory setting is set too high " + databaseMaxMemory +
					" ... let's assume it's not what the user wants to do and restore the default.");
			databaseMaxMemory = Fields.parseLong(nodeConfig.getOption("databaseMaxMemory").getDefault());
		}

		if (storeType.equals("salt-hash")) {
			initRAMFS();
			initSaltHashFS(suffix, false, null);
		} else if (storeType.equals("bdb-index")) {
			initBDBFS(suffix);
		} else {
			initRAMFS();
		}

		nodeStats = new NodeStats(this, sortOrder, new SubConfig("node.load", config), obwLimit, ibwLimit, nodeDir);

		clientCore = new NodeClientCore(this, config, nodeConfig, nodeDir, getDarknetPortNumber(), sortOrder, oldConfig, fproxyConfig, toadlets, nodeDBHandle, db);

		if(databaseAwaitingPassword) createPasswordUserAlert();
		if(notEnoughSpaceForAutoCrypt) createAutoCryptFailedUserAlert();

		netid = new NetworkIDManager(this);

		// Client cache

		// Default is 10MB, in memory only. The wizard will change this.

		nodeConfig.register("clientCacheType", "ram", sortOrder++, true, true, "Node.clientCacheType", "Node.clientCacheTypeLong", new ClientCacheTypeCallback());

		clientCacheType = nodeConfig.getString("clientCacheType");

		nodeConfig.register("clientCacheSize", "10M", sortOrder++, false, true, "Node.clientCacheSize", "Node.clientCacheSizeLong",
				new LongCallback() {

					@Override
					public Long get() {
						return maxTotalClientCacheSize;
					}

					@Override
					public void set(Long storeSize) throws InvalidConfigValueException {
						if((storeSize < 0))
							throw new InvalidConfigValueException(l10n("invalidStoreSize"));
						long newMaxStoreKeys = storeSize / sizePerKey;
						if(newMaxStoreKeys == maxClientCacheKeys) return;
						// Update each datastore
						synchronized(Node.this) {
							maxTotalClientCacheSize = storeSize;
							maxClientCacheKeys = newMaxStoreKeys;
						}
						try {
							chkClientcache.setMaxKeys(maxClientCacheKeys, storeForceBigShrinks);
							pubKeyClientcache.setMaxKeys(maxClientCacheKeys, storeForceBigShrinks);
							sskClientcache.setMaxKeys(maxClientCacheKeys, storeForceBigShrinks);
						} catch (IOException e) {
							// FIXME we need to be able to tell the user.
							Logger.error(this, "Caught "+e+" resizing the clientcache", e);
							System.err.println("Caught "+e+" resizing the clientcache");
							e.printStackTrace();
						} catch (DatabaseException e) {
							Logger.error(this, "Caught "+e+" resizing the clientcache", e);
							System.err.println("Caught "+e+" resizing the clientcache");
							e.printStackTrace();
						}
					}
		}, true);

		maxTotalClientCacheSize = nodeConfig.getLong("clientCacheSize");

		if(maxTotalClientCacheSize < 0) {
			throw new NodeInitException(NodeInitException.EXIT_INVALID_STORE_SIZE, "Invalid client cache size");
		}

		maxClientCacheKeys = maxTotalClientCacheSize / sizePerKey;

		boolean startedClientCache = false;

		boolean shouldWriteConfig = false;

		byte[] databaseKey = null;
		MasterKeys keys = null;

		for(int i=0;i<2 && !startedClientCache; i++) {
		if (clientCacheType.equals("salt-hash")) {

			byte[] clientCacheKey = null;
			try {
				if(securityLevels.physicalThreatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM) {
					clientCacheKey = new byte[32];
					random.nextBytes(clientCacheKey);
				} else {
					keys = MasterKeys.read(masterKeysFile, random, "");
					clientCacheKey = keys.clientCacheMasterKey;
					if(securityLevels.getPhysicalThreatLevel() == PHYSICAL_THREAT_LEVEL.HIGH) {
						System.err.println("Physical threat level is set to HIGH but no password, resetting to NORMAL - probably timing glitch");
						securityLevels.resetPhysicalThreatLevel(PHYSICAL_THREAT_LEVEL.NORMAL);
						databaseKey = keys.databaseKey;
						shouldWriteConfig = true;
					} else {
						keys.clearAllNotClientCacheKey();
					}
				}
				initSaltHashClientCacheFS(suffix, false, clientCacheKey);
				startedClientCache = true;
			} catch (MasterKeysWrongPasswordException e) {
				System.err.println("Cannot open client-cache, it is passworded");
				setClientCacheAwaitingPassword();
				break;
			} catch (MasterKeysFileSizeException e) {
				System.err.println("Impossible: master keys file "+masterKeysFile+" too " + e.sizeToString() + "! Deleting to enable startup, but you will lose your client cache.");
				masterKeysFile.delete();
			} catch (IOException e) {
				break;
			} finally {
				MasterKeys.clear(clientCacheKey);
			}
		} else if(clientCacheType.equals("none")) {
			initNoClientCacheFS();
			startedClientCache = true;
			break;
		} else { // ram
			initRAMClientCacheFS();
			startedClientCache = true;
			break;
		}
		}
		if(!startedClientCache)
			initRAMClientCacheFS();

		if(db == null && databaseKey != null)  {
			try {
				lateSetupDatabase(databaseKey);
			} catch (MasterKeysWrongPasswordException e2) {
				System.err.println("Impossible: "+e2);
				e2.printStackTrace();
			} catch (MasterKeysFileSizeException e2) {
				System.err.println("Impossible: "+e2);
				e2.printStackTrace();
			} catch (IOException e2) {
				System.err.println("Unable to load database: "+e2);
				e2.printStackTrace();
			}
			keys.clearAll();
		}

		nodeConfig.register("useSlashdotCache", true, sortOrder++, true, false, "Node.useSlashdotCache", "Node.useSlashdotCacheLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return useSlashdotCache;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				useSlashdotCache = val;
			}

		});
		useSlashdotCache = nodeConfig.getBoolean("useSlashdotCache");

		nodeConfig.register("writeLocalToDatastore", false, sortOrder++, true, false, "Node.writeLocalToDatastore", "Node.writeLocalToDatastoreLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return writeLocalToDatastore;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				writeLocalToDatastore = val;
			}

		});

		writeLocalToDatastore = nodeConfig.getBoolean("writeLocalToDatastore");

		// LOW network *and* physical seclevel = writeLocalToDatastore

		securityLevels.addNetworkThreatLevelListener(new SecurityLevelListener<NETWORK_THREAT_LEVEL>() {

			public void onChange(NETWORK_THREAT_LEVEL oldLevel, NETWORK_THREAT_LEVEL newLevel) {
				if(newLevel == NETWORK_THREAT_LEVEL.LOW && securityLevels.getPhysicalThreatLevel() == PHYSICAL_THREAT_LEVEL.LOW)
					writeLocalToDatastore = true;
				else
					writeLocalToDatastore = false;
			}

		});

		securityLevels.addPhysicalThreatLevelListener(new SecurityLevelListener<PHYSICAL_THREAT_LEVEL>() {

			public void onChange(PHYSICAL_THREAT_LEVEL oldLevel, PHYSICAL_THREAT_LEVEL newLevel) {
				if(newLevel == PHYSICAL_THREAT_LEVEL.LOW && securityLevels.getNetworkThreatLevel() == NETWORK_THREAT_LEVEL.LOW)
					writeLocalToDatastore = true;
				else
					writeLocalToDatastore = false;
			}

		});

		nodeConfig.register("slashdotCacheLifetime", 30*60*1000L, sortOrder++, true, false, "Node.slashdotCacheLifetime", "Node.slashdotCacheLifetimeLong", new LongCallback() {

			@Override
			public Long get() {
				return chkSlashdotcacheStore.getLifetime();
			}

			@Override
			public void set(Long val) throws InvalidConfigValueException, NodeNeedRestartException {
				if(val < 0) throw new InvalidConfigValueException("Must be positive!");
				chkSlashdotcacheStore.setLifetime(val);
				pubKeySlashdotcacheStore.setLifetime(val);
				sskSlashdotcacheStore.setLifetime(val);
			}

		}, false);

		long slashdotCacheLifetime = nodeConfig.getLong("slashdotCacheLifetime");

		nodeConfig.register("slashdotCacheSize", "10M", sortOrder++, false, true, "Node.slashdotCacheSize", "Node.slashdotCacheSizeLong",
				new LongCallback() {

					@Override
					public Long get() {
						return maxSlashdotCacheSize;
					}

					@Override
					public void set(Long storeSize) throws InvalidConfigValueException {
						if((storeSize < 0))
							throw new InvalidConfigValueException(l10n("invalidStoreSize"));
						int newMaxStoreKeys = (int) Math.min(storeSize / sizePerKey, Integer.MAX_VALUE);
						if(newMaxStoreKeys == maxSlashdotCacheKeys) return;
						// Update each datastore
						synchronized(Node.this) {
							maxSlashdotCacheSize = storeSize;
							maxSlashdotCacheKeys = newMaxStoreKeys;
						}
						try {
							chkSlashdotcache.setMaxKeys(maxSlashdotCacheKeys, storeForceBigShrinks);
							pubKeySlashdotcache.setMaxKeys(maxSlashdotCacheKeys, storeForceBigShrinks);
							sskSlashdotcache.setMaxKeys(maxSlashdotCacheKeys, storeForceBigShrinks);
						} catch (IOException e) {
							// FIXME we need to be able to tell the user.
							Logger.error(this, "Caught "+e+" resizing the slashdotcache", e);
							System.err.println("Caught "+e+" resizing the slashdotcache");
							e.printStackTrace();
						} catch (DatabaseException e) {
							Logger.error(this, "Caught "+e+" resizing the slashdotcache", e);
							System.err.println("Caught "+e+" resizing the slashdotcache");
							e.printStackTrace();
						}
					}
		}, true);

		maxSlashdotCacheSize = nodeConfig.getLong("slashdotCacheSize");

		if(maxSlashdotCacheSize < 0) {
			throw new NodeInitException(NodeInitException.EXIT_INVALID_STORE_SIZE, "Invalid client cache size");
		}

		maxSlashdotCacheKeys = (int) Math.min(maxSlashdotCacheSize / sizePerKey, Integer.MAX_VALUE);

		chkSlashdotcache = new CHKStore();
		chkSlashdotcacheStore = new SlashdotStore<CHKBlock>(chkSlashdotcache, maxSlashdotCacheKeys, slashdotCacheLifetime, PURGE_INTERVAL, ps, this.clientCore.tempBucketFactory);
		pubKeySlashdotcache = new PubkeyStore();
		pubKeySlashdotcacheStore = new SlashdotStore<DSAPublicKey>(pubKeySlashdotcache, maxSlashdotCacheKeys, slashdotCacheLifetime, PURGE_INTERVAL, ps, this.clientCore.tempBucketFactory);
		getPubKey.setLocalSlashdotcache(pubKeySlashdotcache);
		sskSlashdotcache = new SSKStore(getPubKey);
		sskSlashdotcacheStore = new SlashdotStore<SSKBlock>(sskSlashdotcache, maxSlashdotCacheKeys, slashdotCacheLifetime, PURGE_INTERVAL, ps, this.clientCore.tempBucketFactory);

		// MAXIMUM seclevel = no slashdot cache.

		securityLevels.addNetworkThreatLevelListener(new SecurityLevelListener<NETWORK_THREAT_LEVEL>() {

			public void onChange(NETWORK_THREAT_LEVEL oldLevel, NETWORK_THREAT_LEVEL newLevel) {
				if(newLevel == NETWORK_THREAT_LEVEL.MAXIMUM)
					useSlashdotCache = false;
				else if(oldLevel == NETWORK_THREAT_LEVEL.MAXIMUM)
					useSlashdotCache = true;
			}

		});

		nodeConfig.finishedInitialization();
		if(shouldWriteConfig)
			config.store();
		writeNodeFile();

		// Initialize the plugin manager
		Logger.normal(this, "Initializing Plugin Manager");
		System.out.println("Initializing Plugin Manager");
		pluginManager = new PluginManager(this, lastVersion);
		
		shutdownHook.addLateJob(new NativeThread("Shutdown plugins", NativeThread.HIGH_PRIORITY, true) {
			public void realRun() {
				pluginManager.stop(30*1000); // FIXME make it configurable??
			}
		});

		// FIXME
		// Short timeouts and JVM timeouts with nothing more said than the above have been seen...
		// I don't know why... need a stack dump...
		// For now just give it an extra 2 minutes. If it doesn't start in that time,
		// it's likely (on reports so far) that a restart will fix it.
		// And we have to get a build out because ALL plugins are now failing to load,
		// including the absolutely essential (for most nodes) JSTUN and UPnP.
		WrapperManager.signalStarting(120*1000);

		FetchContext ctx = clientCore.makeClient((short)0, true).getFetchContext();

		ctx.allowSplitfiles = false;
		ctx.dontEnterImplicitArchives = true;
		ctx.maxArchiveRestarts = 0;
		ctx.maxMetadataSize = 256;
		ctx.maxNonSplitfileRetries = 10;
		ctx.maxOutputLength = 4096;
		ctx.maxRecursionLevel = 2;
		ctx.maxTempLength = 4096;

		this.arkFetcherContext = ctx;

		// Node updater support

		System.out.println("Initializing Node Updater");
		try {
			nodeUpdater = NodeUpdateManager.maybeCreate(this, config);
		} catch (InvalidConfigValueException e) {
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_UPDATER, "Could not create Updater: "+e);
		}

		registerNodeToNodeMessageListener(N2N_MESSAGE_TYPE_FPROXY, fproxyN2NMListener);
		registerNodeToNodeMessageListener(Node.N2N_MESSAGE_TYPE_DIFFNODEREF, diffNoderefListener);

		// FIXME this is a hack
		// toadlet server should start after all initialized
		// see NodeClientCore line 437
		if (toadlets.isEnabled()) {
			toadlets.finishStart();
			toadlets.createFproxy();
			toadlets.removeStartupToadlet();
		}

		Logger.normal(this, "Node constructor completed");
		System.out.println("Node constructor completed");
	}

	public void lateSetupDatabase(byte[] databaseKey) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
		if(db != null) return;
		System.out.println("Starting late database initialisation");
		setupDatabase(databaseKey);
		nodeDBHandle = darknetCrypto.getNodeHandle(db);

		if(db != null) {
			db.commit();
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "COMMITTED");
			try {
				if(!clientCore.lateInitDatabase(nodeDBHandle, db))
					failLateInitDatabase();
			} catch (NodeInitException e) {
				failLateInitDatabase();
			}
		}
	}

	private void failLateInitDatabase() {
		System.err.println("Failed late initialisation of database, closing...");
		db.close();
		db = null;
	}

	private boolean databaseEncrypted;

	private void setupDatabase(byte[] databaseKey) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
		/* FIXME: Backup the database! */
		Configuration dbConfig = Db4o.newConfiguration();
		/* On my db4o test node with lots of downloads, and several days old, com.db4o.internal.freespace.FreeSlotNode
		 * used 73MB out of the 128MB limit (117MB used). This memory was not reclaimed despite constant garbage collection.
		 * This is unacceptable, hence btree freespace. */
		dbConfig.freespace().useBTreeSystem();
		dbConfig.objectClass(freenet.client.async.PersistentCooldownQueueItem.class).objectField("key").indexed(true);
		dbConfig.objectClass(freenet.client.async.PersistentCooldownQueueItem.class).objectField("keyAsBytes").indexed(true);
		dbConfig.objectClass(freenet.client.async.PersistentCooldownQueueItem.class).objectField("time").indexed(true);
		dbConfig.objectClass(freenet.client.async.RegisterMe.class).objectField("core").indexed(true);
		dbConfig.objectClass(freenet.client.async.RegisterMe.class).objectField("priority").indexed(true);
		dbConfig.objectClass(freenet.client.async.PersistentCooldownQueueItem.class).objectField("time").indexed(true);
		dbConfig.objectClass(freenet.client.FECJob.class).objectField("priority").indexed(true);
		dbConfig.objectClass(freenet.client.FECJob.class).objectField("addedTime").indexed(true);
		dbConfig.objectClass(freenet.client.FECJob.class).objectField("queue").indexed(true);
		dbConfig.objectClass(freenet.client.async.InsertCompressor.class).objectField("nodeDBHandle").indexed(true);
		dbConfig.objectClass(freenet.node.fcp.FCPClient.class).objectField("name").indexed(true);
		dbConfig.objectClass(freenet.client.async.DatastoreCheckerItem.class).objectField("prio").indexed(true);
		dbConfig.objectClass(freenet.client.async.DatastoreCheckerItem.class).objectField("getter").indexed(true);
		dbConfig.objectClass(freenet.support.io.PersistentBlobTempBucketTag.class).objectField("index").indexed(true);
		dbConfig.objectClass(freenet.support.io.PersistentBlobTempBucketTag.class).objectField("bucket").indexed(true);
		dbConfig.objectClass(freenet.support.io.PersistentBlobTempBucketTag.class).objectField("factory").indexed(true);
		dbConfig.objectClass(freenet.support.io.PersistentBlobTempBucketTag.class).objectField("isFree").indexed(true);
		dbConfig.objectClass(freenet.client.FetchException.class).cascadeOnDelete(true);
		dbConfig.objectClass(PluginStore.class).cascadeOnDelete(true);
		/*
		 * HashMap: don't enable cascade on update/delete/activate, db4o handles this
		 * internally through the TMap translator.
		 */
		// LAZY appears to cause ClassCastException's relating to db4o objects inside db4o code. :(
		// Also it causes duplicates if we activate immediately.
		// And the performance gain for e.g. RegisterMeRunner isn't that great.
//		dbConfig.queries().evaluationMode(QueryEvaluationMode.LAZY);
		dbConfig.messageLevel(1);
		dbConfig.activationDepth(1);
		/* TURN OFF SHUTDOWN HOOK.
		 * The shutdown hook does auto-commit. We do NOT want auto-commit: if a
		 * transaction hasn't commit()ed, it's not safe to commit it. For example,
		 * a splitfile is started, gets half way through, then we shut down.
		 * The shutdown hook commits the half-finished transaction. When we start
		 * back up, we assume the whole transaction has been committed, and end
		 * up only registering the proportion of segments for which a RegisterMe
		 * has already been created. Yes, this has happened, yes, it sucks.
		 * Add our own hook to rollback and close... */
		dbConfig.automaticShutDown(false);
		/* Block size 8 should have minimal impact since pointers are this
		 * long, and allows databases of up to 16GB.
		 * FIXME make configurable by user. */
		dbConfig.blockSize(8);
		dbConfig.diagnostic().addListener(new DiagnosticListener() {

			public void onDiagnostic(Diagnostic arg0) {
				if(arg0 instanceof ClassHasNoFields)
					return; // Ignore
				if(arg0 instanceof DiagnosticBase) {
					DiagnosticBase d = (DiagnosticBase) arg0;
					Logger.debug(this, "Diagnostic: "+d.getClass()+" : "+d.problem()+" : "+d.solution()+" : "+d.reason(), new Exception("debug"));
				} else
					Logger.debug(this, "Diagnostic: "+arg0+" : "+arg0.getClass(), new Exception("debug"));
			}
		});

		System.err.println("Optimise native queries: "+dbConfig.optimizeNativeQueries());
		System.err.println("Query activation depth: "+dbConfig.activationDepth());
		ObjectContainer database;

		try {
			if(securityLevels.getPhysicalThreatLevel() == PHYSICAL_THREAT_LEVEL.MAXIMUM) {
				databaseKey = new byte[32];
				random.nextBytes(databaseKey);
				FileUtil.secureDelete(dbFileCrypt, random);
				FileUtil.secureDelete(dbFile, random);
				database = openCryptDatabase(dbConfig, databaseKey);
				synchronized(this) {
					databaseEncrypted = true;
				}
			} else if(dbFile.exists() && securityLevels.getPhysicalThreatLevel() == PHYSICAL_THREAT_LEVEL.LOW) {
				maybeDefragmentDatabase(dbConfig, dbFile);
				// Just open it.
				database = Db4o.openFile(dbConfig, dbFile.toString());
				synchronized(this) {
					databaseEncrypted = false;
				}
			} else if(dbFileCrypt.exists() && securityLevels.getPhysicalThreatLevel() == PHYSICAL_THREAT_LEVEL.LOW && autoChangeDatabaseEncryption && enoughSpaceForAutoChangeEncryption(dbFileCrypt, false)) {
				// Migrate the encrypted file to plaintext, if we have the key
				if(databaseKey == null) {
					// Try with no password
					MasterKeys keys;
					try {
						keys = MasterKeys.read(masterKeysFile, random, "");
					} catch (MasterKeysWrongPasswordException e) {
						// User probably changed it in the config file
						System.err.println("Unable to decrypt the node.db4o. Please enter the correct password and set the physical security level to LOW via the GUI.");
						securityLevels.setThreatLevel(PHYSICAL_THREAT_LEVEL.HIGH);
						throw e;
					}
					databaseKey = keys.databaseKey;
					keys.clearAllNotDatabaseKey();
				}
				System.err.println("Decrypting the old node.db4o.crypt ...");
				IoAdapter baseAdapter = new RandomAccessFileAdapter();
				EncryptingIoAdapter adapter =
					new EncryptingIoAdapter(baseAdapter, databaseKey, random);
				File tempFile = new File(dbFile.getPath()+".tmp");
				tempFile.deleteOnExit();
				FileOutputStream fos = new FileOutputStream(tempFile);
				EncryptingIoAdapter readAdapter =
					(EncryptingIoAdapter) adapter.open(dbFileCrypt.toString(), false, 0, true);
				long length = readAdapter.getLength();
				// Estimate approx 1 byte/sec.
				WrapperManager.signalStarting((int)Math.min(24*60*60*1000, 300*1000+length));
				byte[] buf = new byte[65536];
				long read = 0;
				while(read < length) {
					int bytes = (int) Math.min(buf.length, length - read);
					bytes = readAdapter.read(buf, bytes);
					if(bytes < 0) throw new EOFException();
					read += bytes;
					fos.write(buf, 0, bytes);
				}
				fos.close();
				readAdapter.close();
				tempFile.renameTo(dbFile);
				dbFileCrypt.delete();
				database = Db4o.openFile(dbConfig, dbFile.toString());
				System.err.println("Completed decrypting the old node.db4o.crypt.");
				synchronized(this) {
					databaseEncrypted = false;
				}
			} else if(dbFile.exists() && securityLevels.getPhysicalThreatLevel() != PHYSICAL_THREAT_LEVEL.LOW && autoChangeDatabaseEncryption && enoughSpaceForAutoChangeEncryption(dbFile, true)) {
				// Migrate the unencrypted file to ciphertext.
				// This will always succeed short of I/O errors.
				maybeDefragmentDatabase(dbConfig, dbFile);
				if(databaseKey == null) {
					// Try with no password
					MasterKeys keys;
					keys = MasterKeys.read(masterKeysFile, random, "");
					databaseKey = keys.databaseKey;
					keys.clearAllNotDatabaseKey();
				}
				System.err.println("Encrypting the old node.db4o ...");
				IoAdapter baseAdapter = new RandomAccessFileAdapter();
				EncryptingIoAdapter adapter =
					new EncryptingIoAdapter(baseAdapter, databaseKey, random);
				File tempFile = new File(dbFileCrypt.getPath()+".tmp");
				tempFile.delete();
				tempFile.deleteOnExit();
				EncryptingIoAdapter readAdapter =
					(EncryptingIoAdapter) adapter.open(tempFile.getPath(), false, 0, false);
				FileInputStream fis = new FileInputStream(dbFile);
				long length = dbFile.length();
				// Estimate approx 1 byte/sec.
				WrapperManager.signalStarting((int)Math.min(24*60*60*1000, 300*1000+length));
				byte[] buf = new byte[65536];
				long read = 0;
				while(read < length) {
					int bytes = (int) Math.min(buf.length, length - read);
					bytes = fis.read(buf, 0, bytes);
					if(bytes < 0) throw new EOFException();
					read += bytes;
					readAdapter.write(buf, bytes);
				}
				fis.close();
				readAdapter.close();
				tempFile.renameTo(dbFileCrypt);
				FileUtil.secureDelete(dbFile, random);
				System.err.println("Completed encrypting the old node.db4o.");
				database = openCryptDatabase(dbConfig, databaseKey);
				synchronized(this) {
					databaseEncrypted = true;
				}
			} else if(dbFileCrypt.exists() && !dbFile.exists()) {
				// Open encrypted, regardless of seclevel.
				if(databaseKey == null) {
					// Try with no password
					MasterKeys keys;
					keys = MasterKeys.read(masterKeysFile, random, "");
					databaseKey = keys.databaseKey;
					keys.clearAllNotDatabaseKey();
				}
				database = openCryptDatabase(dbConfig, databaseKey);
				synchronized(this) {
					databaseEncrypted = true;
				}
			} else {
				maybeDefragmentDatabase(dbConfig, dbFile);
				// Open unencrypted.
				database = Db4o.openFile(dbConfig, dbFile.toString());
				synchronized(this) {
					databaseEncrypted = false;
				}
			}
		} catch (Db4oException e) {
			database = null;
			System.err.println("Failed to open database: "+e);
			e.printStackTrace();
		}
		// DUMP DATABASE CONTENTS
		if(Logger.shouldLog(Logger.DEBUG, ClientRequestScheduler.class) && database != null) {
		try {
		System.err.println("DUMPING DATABASE CONTENTS:");
		ObjectSet<Object> contents = database.queryByExample(new Object());
		Map<String,Integer> map = new HashMap<String, Integer>();
		Iterator<Object> i = contents.iterator();
		while(i.hasNext()) {
			Object o = i.next();
			String name = o.getClass().getName();
			if((map.get(name)) != null) {
				map.put(name, map.get(name)+1);
			} else {
				map.put(name, 1);
			}
			// Activated to depth 1
			try {
				Logger.minor(this, "DATABASE: "+o.getClass()+":"+o+":"+database.ext().getID(o));
			} catch (Throwable t) {
				Logger.minor(this, "CAUGHT "+t+" FOR CLASS "+o.getClass());
			}
			database.deactivate(o, 1);
		}
		int total = 0;
		for(Map.Entry<String,Integer> entry : map.entrySet()) {
			System.err.println(entry.getKey()+" : "+entry.getValue());
			total += entry.getValue();
		}

		// Now dump the SplitFileInserterSegment's.
		ObjectSet<SplitFileInserterSegment> segments = database.query(SplitFileInserterSegment.class);
		for(SplitFileInserterSegment seg : segments) {
			try {
			database.activate(seg, 1);
			boolean finished = seg.isFinished();
			boolean cancelled = seg.isCancelled(database);
			boolean empty = seg.isEmpty(database);
			boolean encoded = seg.isEncoded();
			boolean started = seg.isStarted();
			System.out.println("Segment "+seg+" finished="+finished+" cancelled="+cancelled+" empty="+empty+" encoded="+encoded+" size="+seg.countDataBlocks()+" data "+seg.countCheckBlocks()+" check started="+started);

			if(!finished && !encoded) {
				System.out.println("Not finished and not encoded: "+seg);
				// Basic checks...
				seg.checkHasDataBlocks(true, database);

			}

			database.deactivate(seg, 1);
			} catch (Throwable t) {
				System.out.println("Caught "+t+" processing segment");
				t.printStackTrace();
			}
		}

		FECQueue.dump(database, RequestStarter.NUMBER_OF_PRIORITY_CLASSES);

		// Some structures e.g. collections are sensitive to the activation depth.
		// If they are activated to depth 1, they are broken, and activating them to
		// depth 2 does NOT un-break them! Hence we need to deactivate (above) and
		// GC here...
		System.gc();
		System.runFinalization();
		System.gc();
		System.runFinalization();
		System.err.println("END DATABASE DUMP: "+total+" objects");
		} catch (Db4oException e) {
			System.err.println("Unable to dump database contents. Treating as corrupt database.");
			e.printStackTrace();
			try {
				database.rollback();
			} catch (Throwable t) {} // ignore, closing
			try {
				database.close();
			} catch (Throwable t) {} // ignore, closing
			database = null;
		} catch (IllegalArgumentException e) {
			// Urrrrgh!
			System.err.println("Unable to dump database contents. Treating as corrupt database.");
			e.printStackTrace();
			try {
				database.rollback();
			} catch (Throwable t) {} // ignore, closing
			try {
				database.close();
			} catch (Throwable t) {} // ignore, closing
			database = null;
		}
		}

		db = database;

	}


	private volatile boolean notEnoughSpaceForAutoCrypt = false;
	private volatile boolean notEnoughSpaceIsCrypt = false;
	private volatile long notEnoughSpaceMinimumSpace = 0;

	private boolean enoughSpaceForAutoChangeEncryption(File file, boolean isCrypt) {
		long freeSpace = FileUtil.getFreeSpace(file);
		if(freeSpace == -1) {
			return true; // We hope ... FIXME check the error handling ...
		}
		long minSpace = (long)(file.length() * 1.1) + 10*1024*1024;
		if(freeSpace < minSpace) {
			System.err.println(l10n(isCrypt ? "notEnoughSpaceToAutoEncrypt" : "notEnoughSpaceToAutoDecrypt", new String[] { "size", "file" }, new String[] { SizeUtil.formatSize(minSpace), dbFile.getAbsolutePath() }));
			if(this.clientCore != null && this.clientCore.alerts != null)
				createAutoCryptFailedUserAlert();
			else {
				notEnoughSpaceIsCrypt = isCrypt;
				notEnoughSpaceForAutoCrypt = true;
				notEnoughSpaceMinimumSpace = minSpace;
			}
			return false;
		}
		return true;
	}


	private ObjectContainer openCryptDatabase(Configuration dbConfig, byte[] databaseKey) throws IOException {
		IoAdapter baseAdapter = dbConfig.io();
		if(Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this, "Encrypting database with "+HexUtil.bytesToHex(databaseKey));
		dbConfig.io(new EncryptingIoAdapter(baseAdapter, databaseKey, random));

		maybeDefragmentDatabase(dbConfig, dbFileCrypt);

		ObjectContainer database = Db4o.openFile(dbConfig, dbFileCrypt.toString());
		synchronized(this) {
			databaseAwaitingPassword = false;
		}
		return database;
	}


	private void maybeDefragmentDatabase(Configuration dbConfig, File databaseFile) throws IOException {

		synchronized(this) {
			if(!defragDatabaseOnStartup) return;
		}
		if(!databaseFile.exists()) return;
		long length = databaseFile.length();
		// Estimate approx 1 byte/sec.
		WrapperManager.signalStarting((int)Math.min(24*60*60*1000, 300*1000+length));
		System.err.println("Defragmenting persistent downloads database.");

		File backupFile = new File(databaseFile.getPath()+".tmp");
		backupFile.delete();
		backupFile.deleteOnExit();

		File tmpFile = new File(databaseFile.getPath()+".map");
		tmpFile.delete();
		tmpFile.deleteOnExit();

		DefragmentConfig config=new DefragmentConfig(databaseFile.getPath(),backupFile.getPath(),new BTreeIDMapping(tmpFile.getPath()));
		config.storedClassFilter(new AvailableClassFilter());
		config.db4oConfig(dbConfig);
		Defragment.defrag(config);
		System.err.println("Finalising defragmentation...");
		FileUtil.secureDelete(tmpFile, random);
		FileUtil.secureDelete(backupFile, random);
		System.err.println("Defragment completed.");

		synchronized(this) {
			if(!defragOnce) return;
			defragDatabaseOnStartup = false;
		}
		// Store after startup
		this.executor.execute(new Runnable() {

			public void run() {
				Node.this.config.store();
			}

		}, "Store config");

	}


	public void killMasterKeysFile() throws IOException {
		MasterKeys.killMasterKeys(masterKeysFile, random);
	}


	private void setClientCacheAwaitingPassword() {
		createPasswordUserAlert();
		synchronized(this) {
			clientCacheAwaitingPassword = true;
		}
	}

	private final UserAlert masterPasswordUserAlert = new UserAlert() {

		final long creationTime = System.currentTimeMillis();

		public String anchor() {
			return "password";
		}

		public String dismissButtonText() {
			return null;
		}

		public long getUpdatedTime() {
			return creationTime;
		}

		public FCPMessage getFCPMessage() {
			return new FeedMessage(getTitle(), getShortText(), getText(), getPriorityClass(), getUpdatedTime());
		}

		public HTMLNode getHTMLText() {
			HTMLNode content = new HTMLNode("div");
			SecurityLevelsToadlet.generatePasswordFormPage(false, clientCore.getToadletContainer(), content, false, false, false, null, null);
			return content;
		}

		public short getPriorityClass() {
			return UserAlert.ERROR;
		}

		public String getShortText() {
			return NodeL10n.getBase().getString("SecurityLevels.enterPassword");
		}

		public String getText() {
			return NodeL10n.getBase().getString("SecurityLevels.enterPassword");
		}

		public String getTitle() {
			return NodeL10n.getBase().getString("SecurityLevels.enterPassword");
		}

		public Object getUserIdentifier() {
			return Node.this;
		}

		public boolean isEventNotification() {
			return false;
		}

		public boolean isValid() {
			synchronized(Node.this) {
				return clientCacheAwaitingPassword || databaseAwaitingPassword;
			}
		}

		public void isValid(boolean validity) {
			// Ignore
		}

		public void onDismiss() {
			// Ignore
		}

		public boolean shouldUnregisterOnDismiss() {
			return false;
		}

		public boolean userCanDismiss() {
			return false;
		}

	};
	private boolean xmlRemoteCodeExec;

	private void createPasswordUserAlert() {
		this.clientCore.alerts.register(masterPasswordUserAlert);
	}

	private void createAutoCryptFailedUserAlert() {
		boolean isCrypt = notEnoughSpaceIsCrypt;
		this.clientCore.alerts.register(new SimpleUserAlert(true,
				isCrypt ? l10n("notEnoughSpaceToAutoEncryptTitle") : l10n("notEnoughSpaceToAutoDecryptTitle"),
				l10n((isCrypt ? "notEnoughSpaceToAutoEncrypt" : "notEnoughSpaceToAutoDecrypt"), new String[] { "size", "file" }, new String[] { SizeUtil.formatSize(notEnoughSpaceMinimumSpace), dbFile.getAbsolutePath() }),
				isCrypt ? l10n("notEnoughSpaceToAutoEncryptTitle") : l10n("notEnoughSpaceToAutoDecryptTitle"),
				isCrypt ? UserAlert.ERROR : UserAlert.WARNING));
	}

	private void initRAMClientCacheFS() {
		chkClientcache = new CHKStore();
		new RAMFreenetStore<CHKBlock>(chkClientcache, (int) Math.min(Integer.MAX_VALUE, maxClientCacheKeys));
		pubKeyClientcache = new PubkeyStore();
		new RAMFreenetStore<DSAPublicKey>(pubKeyClientcache, (int) Math.min(Integer.MAX_VALUE, maxClientCacheKeys));
		sskClientcache = new SSKStore(getPubKey);
		new RAMFreenetStore<SSKBlock>(sskClientcache, (int) Math.min(Integer.MAX_VALUE, maxClientCacheKeys));
		envMutableConfig = null;
		this.storeEnvironment = null;
	}

	private void initNoClientCacheFS() {
		chkClientcache = new CHKStore();
		new NullFreenetStore<CHKBlock>(chkClientcache);
		pubKeyClientcache = new PubkeyStore();
		new NullFreenetStore<DSAPublicKey>(pubKeyClientcache);
		sskClientcache = new SSKStore(getPubKey);
		new NullFreenetStore<SSKBlock>(sskClientcache);
		envMutableConfig = null;
		this.storeEnvironment = null;
	}

	private String getStoreSuffix() {
		return "-" + getDarknetPortNumber();
	}

	private void finishInitSaltHashFS(final String suffix, NodeClientCore clientCore) {
		if(clientCore.alerts == null) throw new NullPointerException();
		((SaltedHashFreenetStore<CHKBlock>) chkDatastore.getStore()).setUserAlertManager(clientCore.alerts);
		((SaltedHashFreenetStore<CHKBlock>) chkDatacache.getStore()).setUserAlertManager(clientCore.alerts);
		((SaltedHashFreenetStore<DSAPublicKey>) pubKeyDatastore.getStore()).setUserAlertManager(clientCore.alerts);
		((SaltedHashFreenetStore<DSAPublicKey>) pubKeyDatacache.getStore()).setUserAlertManager(clientCore.alerts);
		((SaltedHashFreenetStore<SSKBlock>) sskDatastore.getStore()).setUserAlertManager(clientCore.alerts);
		((SaltedHashFreenetStore<SSKBlock>) sskDatacache.getStore()).setUserAlertManager(clientCore.alerts);

		if (isBDBStoreExist(suffix)) {
			clientCore.alerts.register(new SimpleUserAlert(true, NodeL10n.getBase().getString("Node.storeSaltHashMigratedShort"),
			        NodeL10n.getBase().getString("Node.storeSaltHashMigratedShort"), NodeL10n.getBase()
			                .getString("Node.storeSaltHashMigratedShort"), UserAlert.MINOR) {

				@Override
				public HTMLNode getHTMLText() {
					HTMLNode div = new HTMLNode("div");
					div.addChild("#", NodeL10n.getBase().getString("Node.storeSaltHashMigrated"));
					HTMLNode ul = div.addChild("ul");

					for (String type : new String[] { "chk", "pubkey", "ssk" })
						for (String storecache : new String[] { "store", "store.keys", "store.lru", "cache",
						        "cache.keys", "cache.lru" }) {
							File f = new File(storeDir, type + suffix + "." + storecache);
							if (f.exists())
								ul.addChild("li", f.getAbsolutePath());
						}

					File dbDir = new File(storeDir, "database" + suffix);
					if (dbDir.exists())
						ul.addChild("li", dbDir.getAbsolutePath());

					return div;
				}

				@Override
				public String getText() {
					StringBuilder sb = new StringBuilder();
					sb.append(NodeL10n.getBase().getString("Node.storeSaltHashMigrated") + " \n");

					for (String type : new String[] { "chk", "pubkey", "ssk" })
						for (String storecache : new String[] { "store", "store.keys", "store.lru", "cache",
						        "cache.keys", "cache.lru" }) {
							File f = new File(storeDir, type + suffix + "." + storecache);
					if (f.exists())
								sb.append(" - ");
							sb.append(f.getAbsolutePath());
							sb.append("\n");
					}
					File dbDir = new File(storeDir, "database" + suffix);
					if (dbDir.exists()) {
						sb.append(" - ");
						sb.append(dbDir.getAbsolutePath());
						sb.append("\n");
					}

					return sb.toString();
				}

				@Override
				public boolean isValid() {
					return isBDBStoreExist(suffix);
				}

				@Override
				public void onDismiss() {
				}

				@Override
				public boolean userCanDismiss() {
					return true;
				}
			});
		}
	}

	private void initRAMFS() {
		chkDatastore = new CHKStore();
		new RAMFreenetStore<CHKBlock>(chkDatastore, (int) Math.min(Integer.MAX_VALUE, maxStoreKeys));
		chkDatacache = new CHKStore();
		new RAMFreenetStore<CHKBlock>(chkDatacache, (int) Math.min(Integer.MAX_VALUE, maxCacheKeys));
		pubKeyDatastore = new PubkeyStore();
		new RAMFreenetStore<DSAPublicKey>(pubKeyDatastore, (int) Math.min(Integer.MAX_VALUE, maxStoreKeys));
		pubKeyDatacache = new PubkeyStore();
		getPubKey.setDataStore(pubKeyDatastore, pubKeyDatacache);
		new RAMFreenetStore<DSAPublicKey>(pubKeyDatacache, (int) Math.min(Integer.MAX_VALUE, maxCacheKeys));
		sskDatastore = new SSKStore(getPubKey);
		new RAMFreenetStore<SSKBlock>(sskDatastore, (int) Math.min(Integer.MAX_VALUE, maxStoreKeys));
		sskDatacache = new SSKStore(getPubKey);
		new RAMFreenetStore<SSKBlock>(sskDatacache, (int) Math.min(Integer.MAX_VALUE, maxCacheKeys));
		envMutableConfig = null;
		this.storeEnvironment = null;
	}

	private boolean isBDBStoreExist(final String suffix) {
		for(String type : new String[] { "chk", "pubkey", "ssk" }) {
			for(String ver : new String[] { "store", "cache" }) {
				for(String ext : new String[] { "", ".keys", ".lru" }) {
					if(new File(storeDir, type + suffix + "." + ver + ext).exists()) return true;
				}
			}
		}
		if(new File(storeDir, "database" + suffix).exists()) return true;
		return false;
    }

	private void initSaltHashFS(final String suffix, boolean dontResizeOnStart, byte[] masterKey) throws NodeInitException {
	    storeEnvironment = null;
		envMutableConfig = null;
		try {
			int bloomSize = storeBloomFilterSize;
			if (bloomSize == -1)
				bloomSize = (int) Math.min(maxTotalDatastoreSize / 2048, Integer.MAX_VALUE);
			int bloomFilterSizeInM = storeBloomFilterCounting ? bloomSize / 6 * 4
			        : (bloomSize + 6) / 6 * 8; // + 6 to make size different, trigger rebuild

			final CHKStore chkDatastore = new CHKStore();
			final SaltedHashFreenetStore<CHKBlock> chkDataFS = makeStore(bloomFilterSizeInM, "CHK", true, chkDatastore, dontResizeOnStart, masterKey);
			final CHKStore chkDatacache = new CHKStore();
			final SaltedHashFreenetStore<CHKBlock> chkCacheFS = makeStore(bloomFilterSizeInM, "CHK", false, chkDatacache, dontResizeOnStart, masterKey);
			chkCacheFS.setAltStore(chkDataFS);
			final PubkeyStore pubKeyDatastore = new PubkeyStore();
			final SaltedHashFreenetStore<DSAPublicKey> pubkeyDataFS = makeStore(bloomFilterSizeInM, "PUBKEY", true, pubKeyDatastore, dontResizeOnStart, masterKey);
			final PubkeyStore pubKeyDatacache = new PubkeyStore();
			final SaltedHashFreenetStore<DSAPublicKey> pubkeyCacheFS = makeStore(bloomFilterSizeInM, "PUBKEY", false, pubKeyDatacache, dontResizeOnStart, masterKey);
			pubkeyCacheFS.setAltStore(pubkeyDataFS);
			final SSKStore sskDatastore = new SSKStore(getPubKey);
			final SaltedHashFreenetStore<SSKBlock> sskDataFS = makeStore(bloomFilterSizeInM, "SSK", true, sskDatastore, dontResizeOnStart, masterKey);
			final SSKStore sskDatacache = new SSKStore(getPubKey);
			final SaltedHashFreenetStore<SSKBlock> sskCacheFS = makeStore(bloomFilterSizeInM, "SSK", false, sskDatacache, dontResizeOnStart, masterKey);
			sskCacheFS.setAltStore(sskDataFS);

			boolean delay =
				chkDataFS.start(ps, false) |
				chkCacheFS.start(ps, false) |
				pubkeyDataFS.start(ps, false) |
				pubkeyCacheFS.start(ps, false) |
				sskDataFS.start(ps, false) |
				sskCacheFS.start(ps, false);

			if(delay) {

				System.err.println("Delayed init of datastore");

				initRAMFS();

				final Runnable migrate = new MigrateOldStoreData(false);

				this.getTicker().queueTimedJob(new Runnable() {

					public void run() {
						System.err.println("Starting delayed init of datastore");
						try {
							chkDataFS.start(ps, true);
							chkCacheFS.start(ps, true);
							pubkeyDataFS.start(ps, true);
							pubkeyCacheFS.start(ps, true);
							sskDataFS.start(ps, true);
							sskCacheFS.start(ps, true);
						} catch (IOException e) {
							Logger.error(this, "Failed to start datastore: "+e, e);
							System.err.println("Failed to start datastore: "+e);
							e.printStackTrace();
							return;
						}

						Node.this.chkDatastore = chkDatastore;
						Node.this.chkDatacache = chkDatacache;
						Node.this.pubKeyDatastore = pubKeyDatastore;
						Node.this.pubKeyDatacache = pubKeyDatacache;
						getPubKey.setDataStore(pubKeyDatastore, pubKeyDatacache);
						Node.this.sskDatastore = sskDatastore;
						Node.this.sskDatacache = sskDatacache;

						finishInitSaltHashFS(suffix, clientCore);

						System.err.println("Finishing delayed init of datastore");
						migrate.run();
					}

				}, "Start store", 0, true, false); // Use Ticker to guarantee that this runs *after* constructors have completed.

			} else {

				Node.this.chkDatastore = chkDatastore;
				Node.this.chkDatacache = chkDatacache;
				Node.this.pubKeyDatastore = pubKeyDatastore;
				Node.this.pubKeyDatacache = pubKeyDatacache;
				getPubKey.setDataStore(pubKeyDatastore, pubKeyDatacache);
				Node.this.sskDatastore = sskDatastore;
				Node.this.sskDatacache = sskDatacache;

				this.getTicker().queueTimedJob(new Runnable() {

					public void run() {
						Node.this.chkDatastore = chkDatastore;
						Node.this.chkDatacache = chkDatacache;
						Node.this.pubKeyDatastore = pubKeyDatastore;
						Node.this.pubKeyDatacache = pubKeyDatacache;
						getPubKey.setDataStore(pubKeyDatastore, pubKeyDatacache);
						Node.this.sskDatastore = sskDatastore;
						Node.this.sskDatacache = sskDatacache;

						finishInitSaltHashFS(suffix, clientCore);
					}

				}, "Start store", 0, true, false);
			}

			File migrationFile = new File(storeDir, "migrated");
			if (!migrationFile.exists()) {
				tryMigrate(chkDataFS, "chk", true, suffix);
				tryMigrate(chkCacheFS, "chk", false, suffix);
				tryMigrate(pubkeyDataFS, "pubkey", true, suffix);
				tryMigrate(pubkeyCacheFS, "pubkey", false, suffix);
				tryMigrate(sskDataFS, "ssk", true, suffix);
				tryMigrate(sskCacheFS, "ssk", false, suffix);
				migrationFile.createNewFile();
			}
		} catch (IOException e) {
			System.err.println("Could not open store: " + e);
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_STORE_OTHER, e.getMessage());
		}
    }

	private void initSaltHashClientCacheFS(final String suffix, boolean dontResizeOnStart, byte[] clientCacheMasterKey) throws NodeInitException {
	    storeEnvironment = null;
		envMutableConfig = null;

		try {
			int bloomSize = (int) Math.min(maxTotalClientCacheSize / 2048, Integer.MAX_VALUE);
			int bloomFilterSizeInM = storeBloomFilterCounting ? bloomSize / 6 * 4
			        : (bloomSize + 6) / 6 * 8; // + 6 to make size different, trigger rebuild

			final CHKStore chkClientcache = new CHKStore();
			final SaltedHashFreenetStore<CHKBlock> chkDataFS = makeClientcache(bloomFilterSizeInM, "CHK", true, chkClientcache, dontResizeOnStart, clientCacheMasterKey);
			final PubkeyStore pubKeyClientcache = new PubkeyStore();
			final SaltedHashFreenetStore<DSAPublicKey> pubkeyDataFS = makeClientcache(bloomFilterSizeInM, "PUBKEY", true, pubKeyClientcache, dontResizeOnStart, clientCacheMasterKey);
			final SSKStore sskClientcache = new SSKStore(getPubKey);
			final SaltedHashFreenetStore<SSKBlock> sskDataFS = makeClientcache(bloomFilterSizeInM, "SSK", true, sskClientcache, dontResizeOnStart, clientCacheMasterKey);

			boolean delay =
				chkDataFS.start(ps, false) |
				pubkeyDataFS.start(ps, false) |
				sskDataFS.start(ps, false);

			if(delay) {

				System.err.println("Delayed init of client-cache");

				initRAMClientCacheFS();

				final Runnable migrate = new MigrateOldStoreData(true);

				getTicker().queueTimedJob(new Runnable() {

					public void run() {
						System.err.println("Starting delayed init of client-cache");
						try {
							chkDataFS.start(ps, true);
							pubkeyDataFS.start(ps, true);
							sskDataFS.start(ps, true);
						} catch (IOException e) {
							Logger.error(this, "Failed to start client-cache: "+e, e);
							System.err.println("Failed to start client-cache: "+e);
							e.printStackTrace();
							return;
						}
						Node.this.chkClientcache = chkClientcache;
						Node.this.pubKeyClientcache = pubKeyClientcache;
						getPubKey.setLocalDataStore(pubKeyClientcache);
						Node.this.sskClientcache = sskClientcache;

						System.err.println("Finishing delayed init of client-cache");
						migrate.run();
					}
				}, "Migrate store", 0, true, false);
			} else {
				Node.this.chkClientcache = chkClientcache;
				Node.this.pubKeyClientcache = pubKeyClientcache;
				getPubKey.setLocalDataStore(pubKeyClientcache);
				Node.this.sskClientcache = sskClientcache;
			}

		} catch (IOException e) {
			System.err.println("Could not open store: " + e);
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_STORE_OTHER, e.getMessage());
		}
    }

	private <T extends StorableBlock> void tryMigrate(SaltedHashFreenetStore<T> chkDataFS, String type, boolean isStore, String suffix) {
		String store = isStore ? "store" : "cache";
		chkDataFS.migrationFrom(//
		        new File(storeDir, type + suffix + "."+store), //
		        new File(storeDir, type + suffix + "."+store+".keys"));
	}

	private <T extends StorableBlock> SaltedHashFreenetStore<T> makeClientcache(int bloomFilterSizeInM, String type, boolean isStore, StoreCallback<T> cb, boolean dontResizeOnStart, byte[] clientCacheMasterKey) throws IOException {
		SaltedHashFreenetStore<T> store = makeStore(bloomFilterSizeInM, type, "clientcache", maxClientCacheKeys, cb, dontResizeOnStart, clientCacheMasterKey);
		return store;
	}

	private <T extends StorableBlock> SaltedHashFreenetStore<T> makeStore(int bloomFilterSizeInM, String type, boolean isStore, StoreCallback<T> cb, boolean dontResizeOnStart, byte[] clientCacheMasterKey) throws IOException {
		String store = isStore ? "store" : "cache";
		long maxKeys = isStore ? maxStoreKeys : maxCacheKeys;
		return makeStore(bloomFilterSizeInM, type, store, maxKeys, cb, dontResizeOnStart, clientCacheMasterKey);
	}

	private <T extends StorableBlock> SaltedHashFreenetStore<T> makeStore(int bloomFilterSizeInM, String type, String store, long maxKeys, StoreCallback<T> cb, boolean lateStart, byte[] clientCacheMasterKey) throws IOException {
		Logger.normal(this, "Initializing "+type+" Data"+store);
		System.out.println("Initializing "+type+" Data"+store+" (" + maxStoreKeys + " keys)");

		SaltedHashFreenetStore<T> fs = SaltedHashFreenetStore.<T>construct(storeDir, type+"-"+store, cb,
		        random, maxKeys, bloomFilterSizeInM, storeBloomFilterCounting, shutdownHook, storePreallocate, storeSaltHashResizeOnStart && !lateStart, lateStart ? ps : null, clientCacheMasterKey);
		cb.setStore(fs);
		return fs;
	}

	private void initBDBFS(final String suffix) throws NodeInitException {
		// Setup datastores
		final EnvironmentConfig envConfig = BerkeleyDBFreenetStore.getBDBConfig();

		final File dbDir = new File(storeDir, "database-"+getDarknetPortNumber());
		dbDir.mkdirs();

		final File reconstructFile = new File(dbDir, "reconstruct");

		Environment env = null;
		EnvironmentMutableConfig mutableConfig;

		// This can take some time
		System.out.println("Starting database...");
		try {
			if(reconstructFile.exists()) {
				reconstructFile.delete();
				throw new DatabaseException();
			}
			// Auto-recovery can take a long time
			WrapperManager.signalStarting(60*60*1000);
			env = new Environment(dbDir, envConfig);
			mutableConfig = env.getConfig();
		} catch (final DatabaseException e) {

			// Close the database
			if(env != null) {
				try {
					env.close();
				} catch (final Throwable t) {
					System.err.println("Error closing database: "+t+" after "+e);
					t.printStackTrace();
				}
			}

			// Delete the database logs

			System.err.println("Deleting old database log files...");

			final File[] files = dbDir.listFiles();
			for(int i=0;i<files.length;i++) {
				final String name = files[i].getName().toLowerCase();
				if(name.endsWith(".jdb") || name.equals("je.lck"))
					if(!files[i].delete())
						System.err.println("Failed to delete old database log file "+files[i]);
			}

			System.err.println("Recovering...");
			// The database is broken
			// We will have to recover from scratch
			try {
				env = new Environment(dbDir, envConfig);
				mutableConfig = env.getConfig();
			} catch (final DatabaseException e1) {
				System.err.println("Could not open store: "+e1);
				e1.printStackTrace();
				System.err.println("Previous error was (tried deleting database and retrying): "+e);
				e.printStackTrace();
				throw new NodeInitException(NodeInitException.EXIT_STORE_OTHER, e1.getMessage());
			}
		}
		storeEnvironment = env;
		envMutableConfig = mutableConfig;

		shutdownHook.addLateJob(new NativeThread("Shutdown bdbje database", NativeThread.HIGH_PRIORITY, true) {
			@Override
			public void realRun() {
				try {
					storeEnvironment.close();
					System.err.println("Successfully closed all datastores.");
				} catch (final Throwable t) {
					System.err.println("Caught "+t+" closing environment");
					t.printStackTrace();
				}
			}
		});
		envMutableConfig.setCacheSize(databaseMaxMemory);
		// http://www.oracle.com/technology/products/berkeley-db/faq/je_faq.html#35

		try {
			storeEnvironment.setMutableConfig(envMutableConfig);
		} catch (final DatabaseException e) {
			System.err.println("Could not set the database configuration: "+e);
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_STORE_OTHER, e.getMessage());
		}

		try {
			Logger.normal(this, "Initializing CHK Datastore");
			System.out.println("Initializing CHK Datastore ("+maxStoreKeys+" keys)");
			chkDatastore = new CHKStore();
			BerkeleyDBFreenetStore.construct(storeDir, true, suffix, maxStoreKeys, StoreType.CHK,
					storeEnvironment, shutdownHook, reconstructFile, chkDatastore, random);
			Logger.normal(this, "Initializing CHK Datacache");
			System.out.println("Initializing CHK Datacache ("+maxCacheKeys+ ':' +maxCacheKeys+" keys)");
			chkDatacache = new CHKStore();
			BerkeleyDBFreenetStore.construct(storeDir, false, suffix, maxCacheKeys, StoreType.CHK,
					storeEnvironment, shutdownHook, reconstructFile, chkDatacache, random);
			Logger.normal(this, "Initializing pubKey Datastore");
			System.out.println("Initializing pubKey Datastore");
			pubKeyDatastore = new PubkeyStore();
			BerkeleyDBFreenetStore.construct(storeDir, true, suffix, maxStoreKeys, StoreType.PUBKEY,
					storeEnvironment, shutdownHook, reconstructFile, pubKeyDatastore, random);
			Logger.normal(this, "Initializing pubKey Datacache");
			System.out.println("Initializing pubKey Datacache ("+maxCacheKeys+" keys)");
			pubKeyDatacache = new PubkeyStore();
			BerkeleyDBFreenetStore.construct(storeDir, false, suffix, maxCacheKeys, StoreType.PUBKEY,
					storeEnvironment, shutdownHook, reconstructFile, pubKeyDatacache, random);
			getPubKey.setDataStore(pubKeyDatastore, pubKeyDatacache);
			Logger.normal(this, "Initializing SSK Datastore");
			System.out.println("Initializing SSK Datastore");
			sskDatastore = new SSKStore(getPubKey);
			BerkeleyDBFreenetStore.construct(storeDir, true, suffix, maxStoreKeys, StoreType.SSK,
					storeEnvironment, shutdownHook, reconstructFile, sskDatastore, random);
			Logger.normal(this, "Initializing SSK Datacache");
			System.out.println("Initializing SSK Datacache ("+maxCacheKeys+" keys)");
			sskDatacache = new SSKStore(getPubKey);
			BerkeleyDBFreenetStore.construct(storeDir, false, suffix, maxStoreKeys, StoreType.SSK,
					storeEnvironment, shutdownHook, reconstructFile, sskDatacache, random);
		} catch (final FileNotFoundException e1) {
			final String msg = "Could not open datastore: "+e1;
			Logger.error(this, msg, e1);
			System.err.println(msg);
			throw new NodeInitException(NodeInitException.EXIT_STORE_FILE_NOT_FOUND, msg);
		} catch (final IOException e1) {
			final String msg = "Could not open datastore: "+e1;
			Logger.error(this, msg, e1);
			System.err.println(msg);
			e1.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_STORE_IOEXCEPTION, msg);
		} catch (final DatabaseException e1) {
			try {
				reconstructFile.createNewFile();
			} catch (final IOException e) {
				System.err.println("Cannot create reconstruct file "+reconstructFile+" : "+e+" - store will not be reconstructed !!!!");
				e.printStackTrace();
			}
			final String msg = "Could not open datastore due to corruption, will attempt to reconstruct on next startup: "+e1;
			Logger.error(this, msg, e1);
			System.err.println(msg);
			e1.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_STORE_RECONSTRUCT, msg);
		}
	}

	public void start(boolean noSwaps) throws NodeInitException {

		dispatcher.start(nodeStats); // must be before usm
		dnsr.start();
		peers.start(); // must be before usm
		nodeStats.start();
		uptime.start();
		failureTable.start();

		darknetCrypto.start();
		if(opennet != null)
			opennet.start();
		ps.start(nodeStats);
		usm.start(ps);

		if(isUsingWrapper()) {
			Logger.normal(this, "Using wrapper correctly: "+nodeStarter);
			System.out.println("Using wrapper correctly: "+nodeStarter);
		} else {
			Logger.error(this, "NOT using wrapper (at least not correctly).  Your freenet-ext.jar <http://downloads.freenetproject.org/alpha/freenet-ext.jar> and/or wrapper.conf <https://emu.freenetproject.org/svn/trunk/apps/installer/installclasspath/config/wrapper.conf> need to be updated.");
			System.out.println("NOT using wrapper (at least not correctly).  Your freenet-ext.jar <http://downloads.freenetproject.org/alpha/freenet-ext.jar> and/or wrapper.conf <https://emu.freenetproject.org/svn/trunk/apps/installer/installclasspath/config/wrapper.conf> need to be updated.");
		}
		Logger.normal(this, "Freenet 0.7.5 Build #"+Version.buildNumber()+" r"+Version.cvsRevision());
		System.out.println("Freenet 0.7.5 Build #"+Version.buildNumber()+" r"+Version.cvsRevision());
		Logger.normal(this, "FNP port is on "+darknetCrypto.getBindTo()+ ':' +getDarknetPortNumber());
		System.out.println("FNP port is on "+darknetCrypto.getBindTo()+ ':' +getDarknetPortNumber());
		// Start services

//		SubConfig pluginManagerConfig = new SubConfig("pluginmanager3", config);
//		pluginManager3 = new freenet.plugin_new.PluginManager(pluginManagerConfig);

		ipDetector.start();

		// Start sending swaps
		lm.startSender();

		// Node Updater
		try{
			Logger.normal(this, "Starting the node updater");
			nodeUpdater.start();
		}catch (Exception e) {
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_START_UPDATER, "Could not start Updater: "+e);
		}

		// Start testnet handler
		if(testnetHandler != null)
			testnetHandler.start();

		/* TODO: Make sure that this is called BEFORE any instances of HTTPFilter are created.
		 * HTTPFilter uses checkForGCJCharConversionBug() which returns the value of the static
		 * variable jvmHasGCJCharConversionBug - and this is initialized in the following function.
		 * If this is not possible then create a separate function to check for the GCJ bug and
		 * call this function earlier.
		 */
		checkForEvilJVMBugs();

		// TODO: implement a "required" version if needed
		if(!nodeUpdater.isEnabled() && (NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER > NodeStarter.extBuildNumber))
			clientCore.alerts.register(new ExtOldAgeUserAlert());
		else if(NodeStarter.extBuildNumber == -1)
			clientCore.alerts.register(new ExtOldAgeUserAlert());

		if(!NativeThread.HAS_ENOUGH_NICE_LEVELS)
			clientCore.alerts.register(new NotEnoughNiceLevelsUserAlert());

		this.clientCore.start(config);

		startDeadUIDChecker();

		// After everything has been created, write the config file back to disk.
		if(config instanceof FreenetFilePersistentConfig) {
			FreenetFilePersistentConfig cfg = (FreenetFilePersistentConfig) config;
			cfg.finishedInit(this.ps);
			cfg.setHasNodeStarted();
		}
		config.store();

		// Process any data in the extra peer data directory
		peers.readExtraPeerData();

		Logger.normal(this, "Started node");

		hasStarted = true;
	}

	private static boolean jvmHasGCJCharConversionBug=false;

	private void checkForEvilJVMBugs() {
		// Now check whether we are likely to get the EvilJVMBug.
		// If we are running a Sun or Blackdown JVM, on Linux, and LD_ASSUME_KERNEL is not set, then we are.

		String jvmVendor = System.getProperty("java.vm.vendor");
		String jvmSpecVendor = System.getProperty("java.specification.vendor","");
		String javaVersion = System.getProperty("java.version");
		String jvmName = System.getProperty("java.vm.name");
		String jvmVersion = System.getProperty("java.vm.version");
		String osName = System.getProperty("os.name");
		String osVersion = System.getProperty("os.version");

		boolean isOpenJDK = false;

		if(jvmName.startsWith("OpenJDK ")) {
			isOpenJDK = true;
			if(javaVersion.startsWith("1.6.0")) {
				String subverString;
				if(jvmVersion.startsWith("14.0-b"))
					subverString = jvmVersion.substring("14.0-b".length());
				else if(jvmVersion.startsWith("1.6.0_0-b"))
					subverString = jvmVersion.substring("1.6.0_0-b".length());
				else
					subverString = null;
				if(subverString != null) {
					int subver;
					try {
						subver = Integer.parseInt(subverString);
					} catch (NumberFormatException e) {
						subver = -1;
					}
				if(subver > -1 && subver < 15) {
					File javaDir = new File(System.getProperty("java.home"));

					// Assume that if the java home dir has been updated since August 11th, we have the fix.

					final Calendar _cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
					_cal.set(2009, Calendar.AUGUST, 11, 0, 0, 0);
					if(javaDir.exists() && javaDir.isDirectory() && javaDir.lastModified() > _cal.getTimeInMillis()) {
						System.err.println("Your Java appears to have been updated, we probably do not have the XML bug (http://www.cert.fi/en/reports/2009/vulnerability2009085.html).");
					} else {
						System.err.println("Old version of OpenJDK detected. It is possible that your Java may be vulnerable to a remote code execution vulnerability. Please update your operating system ASAP. We will not disable plugins because we cannot be sure whether there is a problem.");
						System.err.println("See here: http://www.cert.fi/en/reports/2009/vulnerability2009085.html");
						clientCore.alerts.register(new SimpleUserAlert(false, l10n("openJDKMightBeVulnerableXML"), l10n("openJDKMightBeVulnerableXML"), l10n("openJDKMightBeVulnerableXML"), UserAlert.ERROR));
					}

				}
				}
			}
		}

		boolean isApple;

		if(logMINOR) Logger.minor(this, "JVM vendor: "+jvmVendor+", JVM version: "+javaVersion+", OS name: "+osName+", OS version: "+osVersion);

		if((!isOpenJDK) && (jvmVendor.startsWith("Sun ") || (jvmVendor.startsWith("The FreeBSD Foundation") && jvmSpecVendor.startsWith("Sun ")) || (isApple = jvmVendor.startsWith("Apple ")))) {
			// Sun bugs

			// Spurious OOMs
			// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4855795
			// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=2138757
			// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=2138759
			// Fixed in 1.5.0_10 and 1.4.2_13

			boolean is150 = javaVersion.startsWith("1.5.0_");
			boolean is160 = javaVersion.startsWith("1.6.0_");

			if(is150 || is160) {
				String[] split = javaVersion.split("_");
				String secondPart = split[1];
				if(secondPart.indexOf("-") != -1) {
					split = secondPart.split("-");
					secondPart = split[0];
				}
				int subver = Integer.parseInt(secondPart);

				Logger.minor(this, "JVM version: "+javaVersion+" subver: "+subver+" from "+secondPart);

				if(is150 && subver < 20 || is160 && subver < 15)
					xmlRemoteCodeExec = true;
			}

			if(xmlRemoteCodeExec) {
				System.err.println("Please upgrade your Java to 1.6.0 update 15 or 1.5.0 update 20 IMMEDIATELY!");
				System.err.println("Freenet plugins using XML, including the search function, and Freenet client applications such as Thaw which use XML are vulnerable to remote code execution!");

				clientCore.alerts.register(new SimpleUserAlert(false, l10n("sunJVMxmlRemoteCodeExecTitle"), l10n("sunJVMxmlRemoteCodeExec"), l10n("sunJVMxmlRemoteCodeExecTitle"), UserAlert.CRITICAL_ERROR));
			}

		} else if (jvmVendor.startsWith("Apple ") || jvmVendor.startsWith("\"Apple ")) {
			//Note that Sun does not produce VMs for the Macintosh operating system, dont ask the user to find one...
		} else {
			if(jvmVendor.startsWith("Free Software Foundation")) {
				try {
					javaVersion = System.getProperty("java.version").split(" ")[0].replaceAll("[.]","");
					int jvmVersionInt = Integer.parseInt(javaVersion);

					if(jvmVersionInt <= 422 && jvmVersionInt >= 100) // make sure that no bogus values cause true
						jvmHasGCJCharConversionBug=true;
				}

				catch(Throwable t) {
					Logger.error(this, "GCJ version check is broken!", t);
				}
			}

			clientCore.alerts.register(new SimpleUserAlert(true, l10n("notUsingSunVMTitle"), l10n("notUsingSunVM", new String[] { "vendor", "version" }, new String[] { jvmVendor, javaVersion }), l10n("notUsingSunVMShort"), UserAlert.WARNING));
		}

		if(!isUsingWrapper()) {
			clientCore.alerts.register(new SimpleUserAlert(true, l10n("notUsingWrapperTitle"), l10n("notUsingWrapper"), l10n("notUsingWrapperShort"), UserAlert.WARNING));
		}

	}

	public boolean xmlRemoteCodeExecVuln() {
		return xmlRemoteCodeExec;
	}

	public static boolean checkForGCJCharConversionBug() {
		return jvmHasGCJCharConversionBug; // should be initialized on early startup
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("Node."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("Node."+key, pattern, value);
	}

	private String l10n(String key, String[] pattern, String[] value) {
		return NodeL10n.getBase().getString("Node."+key, pattern, value);
	}

	/**
	 * Export volatile data about the node as a SimpleFieldSet
	 */
	public SimpleFieldSet exportVolatileFieldSet() {
		return nodeStats.exportVolatileFieldSet();
	}

	/**
	 * Do a routed ping of another node on the network by its location.
	 * @param loc2 The location of the other node to ping. It must match
	 * exactly.
	 * @return The number of hops it took to find the node, if it was found.
	 * Otherwise -1.
	 */
	public int routedPing(double loc2, byte[] nodeIdentity) {
		long uid = random.nextLong();
		int initialX = random.nextInt();
		Message m = DMT.createFNPRoutedPing(uid, loc2, maxHTL, initialX, nodeIdentity);
		Logger.normal(this, "Message: "+m);

		dispatcher.handleRouted(m, null);
		// FIXME: might be rejected
		MessageFilter mf1 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedPong).setTimeout(5000);
		try {
			//MessageFilter mf2 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedRejected).setTimeout(5000);
			// Ignore Rejected - let it be retried on other peers
			m = usm.waitFor(mf1/*.or(mf2)*/, null);
		} catch (DisconnectedException e) {
			Logger.normal(this, "Disconnected in waiting for pong");
			return -1;
		}
		if(m == null) return -1;
		if(m.getSpec() == DMT.FNPRoutedRejected) return -1;
		return m.getInt(DMT.COUNTER) - initialX;
	}

	/**
	 * Look for a block in the datastore, as part of a request.
	 * @param key The key to fetch.
	 * @param uid The UID of the request (for logging only).
	 * @param promoteCache Whether to promote the key if found.
	 * @param canReadClientCache If the request is local, we can read the client cache.
	 * @param canWriteClientCache If the request is local, and the client hasn't turned off
	 * writing to the client cache, we can write to the client cache.
	 * @param canWriteDatastore If the request HTL is too high, including if it is local, we
	 * cannot write to the datastore.
	 * @return A KeyBlock for the key requested or null.
	 */
	private KeyBlock makeRequestLocal(Key key, long uid, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean offersOnly) {
		KeyBlock kb = null;

		if (key instanceof NodeCHK) {
			kb = fetch((NodeCHK) key, false, canReadClientCache, canWriteClientCache, canWriteDatastore, null);
		} else if (key instanceof NodeSSK) {
			NodeSSK sskKey = (NodeSSK) key;
			DSAPublicKey pubKey = sskKey.getPubKey();
			if (pubKey == null) {
				pubKey = getPubKey.getKey(sskKey.getPubKeyHash(), canReadClientCache, offersOnly, null);
				if (logMINOR)
					Logger.minor(this, "Fetched pubkey: " + pubKey);
				try {
					sskKey.setPubKey(pubKey);
				} catch (SSKVerifyException e) {
					Logger.error(this, "Error setting pubkey: " + e, e);
				}
			}
			if (pubKey != null) {
				if (logMINOR)
					Logger.minor(this, "Got pubkey: " + pubKey);
				kb = fetch(sskKey, canReadClientCache, canWriteClientCache, canWriteDatastore, false, null);
			} else {
				if (logMINOR)
					Logger.minor(this, "Not found because no pubkey: " + uid);
			}
		} else
			throw new IllegalStateException("Unknown key type: " + key.getClass());

		if (kb != null) {
			// Probably somebody waiting for it. Trip it.
			if (clientCore != null && clientCore.requestStarters != null) {
				if (kb instanceof CHKBlock)
					clientCore.requestStarters.chkFetchScheduler.tripPendingKey(kb);
				else
					clientCore.requestStarters.sskFetchScheduler.tripPendingKey(kb);
			}
			failureTable.onFound(kb);
			return kb;
		}

		return null;
	}

	/**
	 * Check the datastore, then if the key is not in the store,
	 * check whether another node is requesting the same key at
	 * the same HTL, and if all else fails, create a new
	 * RequestSender for the key/htl.
	 * @param closestLocation The closest location to the key so far.
	 * @param localOnly If true, only check the datastore.
	 * @return A KeyBlock if the data is in the store, otherwise
	 * a RequestSender, unless the HTL is 0, in which case NULL.
	 * RequestSender.
	 */
	public Object makeRequestSender(Key key, short htl, long uid, PeerNode source, boolean localOnly, boolean ignoreStore, boolean offersOnly, boolean canReadClientCache, boolean canWriteClientCache) {
		boolean canWriteDatastore = canWriteDatastoreRequest(htl);
		if(logMINOR) Logger.minor(this, "makeRequestSender("+key+ ',' +htl+ ',' +uid+ ',' +source+") on "+getDarknetPortNumber());
		// In store?
		if(!ignoreStore) {
			KeyBlock kb = makeRequestLocal(key, uid, canReadClientCache, canWriteClientCache, canWriteDatastore, offersOnly);
			if (kb != null)
				return kb;
		}
		if(localOnly) return null;
		if(logMINOR) Logger.minor(this, "Not in store locally");

		// Transfer coalescing - match key only as HTL irrelevant
		RequestSender sender = null;
		synchronized(transferringRequestSenders) {
			sender = transferringRequestSenders.get(key);
		}
		if(sender != null) {
			if(logMINOR) Logger.minor(this, "Data already being transferred: "+sender);
			return sender;
		}

		// HTL == 0 => Don't search further
		if(htl == 0) {
			if(logMINOR) Logger.minor(this, "No HTL");
			return null;
		}

		sender = new RequestSender(key, null, htl, uid, this, source, offersOnly, canWriteClientCache, canWriteDatastore);
		sender.start();
		if(logMINOR) Logger.minor(this, "Created new sender: "+sender);
		return sender;
	}

	/** Can we write to the datastore for a given request?
	 * We do not write to the datastore until 2 hops below maximum. This is an average of 4
	 * hops from the originator. Thus, data returned from local requests is never cached,
	 * finally solving The Register's attack, Bloom filter sharing doesn't give away your local
	 * requests and inserts, and *anything starting at high HTL* is not cached, including stuff
	 * from other nodes which hasn't been decremented far enough yet, so it's not ONLY local
	 * requests that don't get cached. */
	boolean canWriteDatastoreRequest(short htl) {
		return htl <= (maxHTL - 2);
	}

	/** Can we write to the datastore for a given insert?
	 * We do not write to the datastore until 3 hops below maximum. This is an average of 5
	 * hops from the originator. Thus, data sent by local inserts is never cached,
	 * finally solving The Register's attack, Bloom filter sharing doesn't give away your local
	 * requests and inserts, and *anything starting at high HTL* is not cached, including stuff
	 * from other nodes which hasn't been decremented far enough yet, so it's not ONLY local
	 * inserts that don't get cached. */
	boolean canWriteDatastoreInsert(short htl) {
		return htl <= (maxHTL - 3);
	}

	/**
	 * Add a transferring RequestSender to our HashMap.
	 */
	public void addTransferringSender(NodeCHK key, RequestSender sender) {
		synchronized(transferringRequestSenders) {
			transferringRequestSenders.put(key, sender);
		}
	}

	void addTransferringRequestHandler(long id) {
		synchronized(transferringRequestHandlers) {
			transferringRequestHandlers.add(id);
		}
	}

	void removeTransferringRequestHandler(long id) {
		synchronized(transferringRequestHandlers) {
			transferringRequestHandlers.remove(id);
		}
	}

	/**
	 * Fetch a block from the datastore.
	 * @param key
	 * @param canReadClientCache
	 * @param canWriteClientCache
	 * @param canWriteDatastore
	 * @param forULPR
	 * @param mustBeMarkedAsPostCachingChanges If true, the key must have the
	 * ENTRY_NEW_BLOCK flag (if saltedhash), indicating that it a) has been added
	 * since the caching changes in 1224 (since we didn't delete the stores), and b)
	 * that it wasn't added due to low network security caching everything, unless we
	 * are currently in low network security mode. Only applies to main store.
	 */
	public KeyBlock fetch(Key key, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, BlockMetadata meta) {
		if(key instanceof NodeSSK)
			return fetch((NodeSSK)key, false, canReadClientCache, canWriteClientCache, canWriteDatastore, forULPR, meta);
		else if(key instanceof NodeCHK)
			return fetch((NodeCHK)key, false, canReadClientCache, canWriteClientCache, canWriteDatastore, forULPR, meta);
		else throw new IllegalArgumentException();
	}

	public SSKBlock fetch(NodeSSK key, boolean dontPromote, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, BlockMetadata meta) {
		if(canReadClientCache) {
			try {
				SSKBlock block = sskClientcache.fetch(key, dontPromote || !canWriteClientCache, canReadClientCache, forULPR, meta);
				if(block != null) return block;
			} catch (IOException e) {
				Logger.error(this, "Could not read from client cache: "+e, e);
			}
		}
		if(forULPR || useSlashdotCache || canReadClientCache) {
			try {
				SSKBlock block = sskSlashdotcache.fetch(key, dontPromote, canReadClientCache, forULPR, meta);
				if(block != null) return block;
			} catch (IOException e) {
				Logger.error(this, "Could not read from slashdot/ULPR cache: "+e, e);
			}
		}
		if(logMINOR) dumpStoreHits();
		try {
			double loc=key.toNormalizedDouble();
			double dist=Location.distance(lm.getLocation(), loc);
			nodeStats.avgRequestLocation.report(loc);
			SSKBlock block = sskDatastore.fetch(key, dontPromote || !canWriteDatastore, canReadClientCache, forULPR, meta);
			if(block == null) {
				SSKStore store = oldSSK;
				if(store != null)
					block = store.fetch(key, dontPromote || !canWriteDatastore, canReadClientCache, forULPR, meta);
			}
			if(block != null) {
				nodeStats.avgStoreSuccess.report(loc);
				if (dist > nodeStats.furthestStoreSuccess)
					nodeStats.furthestStoreSuccess=dist;
				return block;
			}
			block=sskDatacache.fetch(key, dontPromote || !canWriteDatastore, canReadClientCache, forULPR, meta);
			if(block == null) {
				SSKStore store = oldSSKCache;
				if(store != null)
					block = store.fetch(key, dontPromote || !canWriteDatastore, canReadClientCache, forULPR, meta);
			}
			if (block != null) {
				nodeStats.avgCacheSuccess.report(loc);
				if (dist > nodeStats.furthestCacheSuccess)
					nodeStats.furthestCacheSuccess=dist;
			}
			return block;
		} catch (IOException e) {
			Logger.error(this, "Cannot fetch data: "+e, e);
			return null;
		}
	}

	public CHKBlock fetch(NodeCHK key, boolean dontPromote, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR, BlockMetadata meta) {
		if(canReadClientCache) {
			try {
				CHKBlock block = chkClientcache.fetch(key, dontPromote || !canWriteClientCache, meta);
				if(block != null) return block;
			} catch (IOException e) {
				Logger.error(this, "Could not read from client cache: "+e, e);
			}
		}
		if(forULPR || useSlashdotCache || canReadClientCache) {
			try {
				CHKBlock block = chkSlashdotcache.fetch(key, dontPromote, meta);
				if(block != null) return block;
			} catch (IOException e) {
				Logger.error(this, "Could not read from slashdot/ULPR cache: "+e, e);
			}
		}
		if(logMINOR) dumpStoreHits();
		try {
			double loc=key.toNormalizedDouble();
			double dist=Location.distance(lm.getLocation(), loc);
			nodeStats.avgRequestLocation.report(loc);
			CHKBlock block = chkDatastore.fetch(key, dontPromote || !canWriteDatastore, meta);
			if(block == null) {
				CHKStore store = oldCHK;
				if(store != null)
					block = store.fetch(key, dontPromote || !canWriteDatastore, meta);
			}
			if (block != null) {
				nodeStats.avgStoreSuccess.report(loc);
				if (dist > nodeStats.furthestStoreSuccess)
					nodeStats.furthestStoreSuccess=dist;
				return block;
			}
			block=chkDatacache.fetch(key, dontPromote || !canWriteDatastore, meta);
			if(block == null) {
				CHKStore store = oldCHKCache;
				if(store != null)
					block = store.fetch(key, dontPromote || !canWriteDatastore, meta);
			}
			if (block != null) {
				nodeStats.avgCacheSuccess.report(loc);
				if (dist > nodeStats.furthestCacheSuccess)
					nodeStats.furthestCacheSuccess=dist;
			}
			return block;
		} catch (IOException e) {
			Logger.error(this, "Cannot fetch data: "+e, e);
			return null;
		}
	}

	public CHKStore getChkDatacache() {
		return chkDatacache;
	}
	public CHKStore getChkDatastore() {
		return chkDatastore;
	}
	public SSKStore getSskDatacache() {
		return sskDatacache;
	}
	public SSKStore getSskDatastore() {
		return sskDatastore;
	}


	/**
	 * This method returns all statistics info for our data store stats table
	 *
	 * @return map that has an entry for each data store instance type and corresponding stats
	 */
	public Map<DataStoreInstanceType, DataStoreStats> getDataStoreStats() {
		Map<DataStoreInstanceType, DataStoreStats> map = new LinkedHashMap<DataStoreInstanceType, DataStoreStats>();

		map.put(new DataStoreInstanceType(CHK, STORE), new StoreCallbackStats(chkDatastore, nodeStats.chkStoreStats()));
		map.put(new DataStoreInstanceType(CHK, CACHE), new StoreCallbackStats(chkDatacache, nodeStats.chkCacheStats()));
		map.put(new DataStoreInstanceType(CHK, SLASHDOT), new StoreCallbackStats(chkSlashdotcache, new NotAvailNodeStoreStats()));
		map.put(new DataStoreInstanceType(CHK, CLIENT), new StoreCallbackStats(chkClientcache, new NotAvailNodeStoreStats()));

		map.put(new DataStoreInstanceType(SSK, STORE), new StoreCallbackStats(sskDatastore, new NotAvailNodeStoreStats()));
		map.put(new DataStoreInstanceType(SSK, CACHE), new StoreCallbackStats(sskDatacache, new NotAvailNodeStoreStats()));
		map.put(new DataStoreInstanceType(SSK, SLASHDOT), new StoreCallbackStats(sskSlashdotcache, new NotAvailNodeStoreStats()));
		map.put(new DataStoreInstanceType(SSK, CLIENT), new StoreCallbackStats(sskClientcache, new NotAvailNodeStoreStats()));

		map.put(new DataStoreInstanceType(PUB_KEY, STORE), new StoreCallbackStats(pubKeyDatastore, new NotAvailNodeStoreStats()));
		map.put(new DataStoreInstanceType(PUB_KEY, CACHE), new StoreCallbackStats(pubKeyDatacache, new NotAvailNodeStoreStats()));
		map.put(new DataStoreInstanceType(PUB_KEY, SLASHDOT), new StoreCallbackStats(pubKeySlashdotcache, new NotAvailNodeStoreStats()));
		map.put(new DataStoreInstanceType(PUB_KEY, CLIENT), new StoreCallbackStats(pubKeyClientcache, new NotAvailNodeStoreStats()));

		return map;
	}

	public long getMaxTotalKeys() {
		return maxTotalKeys;
	}

	long timeLastDumpedHits;

	public void dumpStoreHits() {
		long now = System.currentTimeMillis();
		if(now - timeLastDumpedHits > 5000) {
			timeLastDumpedHits = now;
		} else return;
		Logger.minor(this, "Distribution of hits and misses over stores:\n"+
				"CHK Datastore: "+chkDatastore.hits()+ '/' +(chkDatastore.hits()+chkDatastore.misses())+ '/' +chkDatastore.keyCount()+
				"\nCHK Datacache: "+chkDatacache.hits()+ '/' +(chkDatacache.hits()+chkDatacache.misses())+ '/' +chkDatacache.keyCount()+
				"\nSSK Datastore: "+sskDatastore.hits()+ '/' +(sskDatastore.hits()+sskDatastore.misses())+ '/' +sskDatastore.keyCount()+
				"\nSSK Datacache: "+sskDatacache.hits()+ '/' +(sskDatacache.hits()+sskDatacache.misses())+ '/' +sskDatacache.keyCount());
	}

	public void storeShallow(CHKBlock block, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR) {
		store(block, false, canWriteClientCache, canWriteDatastore, forULPR);
	}

	/**
	 * Store a datum.
	 * @param block
	 *      a KeyBlock
	 * @param deep If true, insert to the store as well as the cache. Do not set
	 * this to true unless the store results from an insert, and this node is the
	 * closest node to the target; see the description of chkDatastore.
	 */
	public void store(KeyBlock block, boolean deep, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR) throws KeyCollisionException {
		if(block instanceof CHKBlock)
			store((CHKBlock)block, deep, canWriteClientCache, canWriteDatastore, forULPR);
		else if(block instanceof SSKBlock)
			store((SSKBlock)block, deep, false, canWriteClientCache, canWriteDatastore, forULPR);
		else throw new IllegalArgumentException("Unknown keytype ");
	}

	private void store(CHKBlock block, boolean deep, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR) {
		try {
			if(canWriteClientCache) {
				chkClientcache.put(block, false);
			}
			if((forULPR || useSlashdotCache) && !(canWriteDatastore || writeLocalToDatastore))
				chkSlashdotcache.put(block, false);
			if(canWriteDatastore || writeLocalToDatastore) {
				double loc=block.getKey().toNormalizedDouble();
				if(deep) {
					chkDatastore.put(block, !canWriteDatastore);
					nodeStats.avgStoreLocation.report(loc);
				}
				chkDatacache.put(block, !canWriteDatastore);
				nodeStats.avgCacheLocation.report(loc);
			}
			if(canWriteDatastore || forULPR || useSlashdotCache)
				failureTable.onFound(block);
		} catch (IOException e) {
			Logger.error(this, "Cannot store data: "+e, e);
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
		} catch (Throwable t) {
			System.err.println(t);
			t.printStackTrace();
			Logger.error(this, "Caught "+t+" storing data", t);
		}
		if(clientCore != null && clientCore.requestStarters != null)
			clientCore.requestStarters.chkFetchScheduler.tripPendingKey(block);
	}

	/** Store the block if this is a sink. Call for inserts. */
	public void storeInsert(SSKBlock block, boolean deep, boolean overwrite, boolean canWriteClientCache, boolean canWriteDatastore) throws KeyCollisionException {
		store(block, deep, true, canWriteClientCache, canWriteDatastore);
	}

	/** Store only to the cache, and not the store. Called by requests,
	 * as only inserts cause data to be added to the store. */
	public void storeShallow(SSKBlock block, boolean canWriteClientCache, boolean canWriteDatastore, boolean fromULPR) throws KeyCollisionException {
		store(block, false, canWriteClientCache, canWriteDatastore, fromULPR);
	}

	public void store(SSKBlock block, boolean deep, boolean overwrite, boolean canWriteClientCache, boolean canWriteDatastore, boolean forULPR) throws KeyCollisionException {
		try {
			// Store the pubkey before storing the data, otherwise we can get a race condition and
			// end up deleting the SSK data.
			getPubKey.cacheKey((block.getKey()).getPubKeyHash(), (block.getKey()).getPubKey(), deep, canWriteClientCache, canWriteDatastore, forULPR || useSlashdotCache, writeLocalToDatastore);
			if(canWriteClientCache) {
				sskClientcache.put(block, overwrite, false);
			}
			if((forULPR || useSlashdotCache) && !(canWriteDatastore || writeLocalToDatastore))
				sskSlashdotcache.put(block, overwrite, false);
			if(canWriteDatastore || writeLocalToDatastore) {
				if(deep) {
					sskDatastore.put(block, overwrite, !canWriteDatastore);
				}
				sskDatacache.put(block, overwrite, !canWriteDatastore);
			}
			if(canWriteDatastore || forULPR || useSlashdotCache)
				failureTable.onFound(block);
		} catch (IOException e) {
			Logger.error(this, "Cannot store data: "+e, e);
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
		} catch (KeyCollisionException e) {
			throw e;
		} catch (Throwable t) {
			System.err.println(t);
			t.printStackTrace();
			Logger.error(this, "Caught "+t+" storing data", t);
		}
		if(clientCore != null && clientCore.requestStarters != null)
			clientCore.requestStarters.sskFetchScheduler.tripPendingKey(block);
	}

	/**
	 * Remove a sender from the set of currently transferring senders.
	 */
	public void removeTransferringSender(NodeCHK key, RequestSender sender) {
		synchronized(transferringRequestSenders) {
//			RequestSender rs = (RequestSender) transferringRequestSenders.remove(key);
//			if(rs != sender) {
//				Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+" in removeTransferringSender");
//			}

			// Since there is no request coalescing, we only remove it if it matches,
			// and don't complain if it doesn't.
			if(transferringRequestSenders.get(key) == sender)
				transferringRequestSenders.remove(key);
		}
	}

	final boolean decrementAtMax;
	final boolean decrementAtMin;

	/**
	 * Decrement the HTL according to the policy of the given
	 * NodePeer if it is non-null, or do something else if it is
	 * null.
	 */
	public short decrementHTL(PeerNode source, short htl) {
		if(source != null)
			return source.decrementHTL(htl);
		// Otherwise...
		if(htl >= maxHTL) htl = maxHTL;
		if(htl <= 0) {
			return 0;
		}
		if(htl == maxHTL) {
			if(decrementAtMax || disableProbabilisticHTLs) htl--;
			return htl;
		}
		if(htl == 1) {
			if(decrementAtMin || disableProbabilisticHTLs) htl--;
			return htl;
		}
		return --htl;
	}

	/**
	 * Fetch or create an CHKInsertSender for a given key/htl.
	 * @param key The key to be inserted.
	 * @param htl The current HTL. We can't coalesce inserts across
	 * HTL's.
	 * @param uid The UID of the caller's request chain, or a new
	 * one. This is obviously not used if there is already an
	 * CHKInsertSender running.
	 * @param source The node that sent the InsertRequest, or null
	 * if it originated locally.
	 */
	public CHKInsertSender makeInsertSender(NodeCHK key, short htl, long uid, PeerNode source,
			byte[] headers, PartiallyReceivedBlock prb, boolean fromStore, boolean canWriteClientCache, boolean forkOnCacheable) {
		if(logMINOR) Logger.minor(this, "makeInsertSender("+key+ ',' +htl+ ',' +uid+ ',' +source+",...,"+fromStore);
		CHKInsertSender is = null;
		is = new CHKInsertSender(key, uid, headers, htl, source, this, prb, fromStore, canWriteClientCache, forkOnCacheable);
		is.start();
		// CHKInsertSender adds itself to insertSenders
		return is;
	}

	/**
	 * Fetch or create an SSKInsertSender for a given key/htl.
	 * @param key The key to be inserted.
	 * @param htl The current HTL. We can't coalesce inserts across
	 * HTL's.
	 * @param uid The UID of the caller's request chain, or a new
	 * one. This is obviously not used if there is already an
	 * SSKInsertSender running.
	 * @param source The node that sent the InsertRequest, or null
	 * if it originated locally.
	 */
	public SSKInsertSender makeInsertSender(SSKBlock block, short htl, long uid, PeerNode source,
			boolean fromStore, boolean canWriteClientCache, boolean canWriteDatastore, boolean forkOnCacheable) {
		NodeSSK key = block.getKey();
		if(key.getPubKey() == null) {
			throw new IllegalArgumentException("No pub key when inserting");
		}

		getPubKey.cacheKey(key.getPubKeyHash(), key.getPubKey(), false, canWriteClientCache, canWriteDatastore, false, writeLocalToDatastore);
		Logger.minor(this, "makeInsertSender("+key+ ',' +htl+ ',' +uid+ ',' +source+",...,"+fromStore);
		SSKInsertSender is = null;
		is = new SSKInsertSender(block, uid, htl, source, this, fromStore, canWriteClientCache, forkOnCacheable);
		is.start();
		return is;
	}

	public boolean lockUID(long uid, boolean ssk, boolean insert, boolean offerReply, boolean local, UIDTag tag) {
		synchronized(runningUIDs) {
			if(runningUIDs.containsKey(uid)) return false; // Already present.
			runningUIDs.put(uid, tag);
		}
		// If these are switched around, we must remember to remove from both.
		if(offerReply) {
			HashMap<Long,OfferReplyTag> map = getOfferTracker(ssk);
			innerLock(map, (OfferReplyTag)tag, uid, ssk, insert, offerReply, local);
		} else if(insert) {
			HashMap<Long,InsertTag> map = getInsertTracker(ssk,local);
			innerLock(map, (InsertTag)tag, uid, ssk, insert, offerReply, local);
		} else {
			HashMap<Long,RequestTag> map = getRequestTracker(ssk,local);
			innerLock(map, (RequestTag)tag, uid, ssk, insert, offerReply, local);
		}
		return true;
	}

	private<T extends UIDTag> void innerLock(HashMap<Long, T> map, T tag, Long uid, boolean ssk, boolean insert, boolean offerReply, boolean local) {
		synchronized(map) {
			if(logMINOR) Logger.minor(this, "Locking "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+map.size(), new Exception("debug"));
			if(map.containsKey(uid)) {
				Logger.error(this, "Already have UID in specific map ("+ssk+","+insert+","+offerReply+","+local+") but not in general map: trying to register "+tag+" but already have "+map.get(uid));
			}
			map.put(uid, tag);
			if(logMINOR) Logger.minor(this, "Locked "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+map.size());
		}
	}

	public void unlockUID(long uid, boolean ssk, boolean insert, boolean canFail, boolean offerReply, boolean local, UIDTag tag) {
		completed(uid);

		if(offerReply) {
			HashMap<Long,OfferReplyTag> map = getOfferTracker(ssk);
			innerUnlock(map, (OfferReplyTag)tag, uid, ssk, insert, offerReply, local, canFail);
		} else if(insert) {
			HashMap<Long,InsertTag> map = getInsertTracker(ssk,local);
			innerUnlock(map, (InsertTag)tag, uid, ssk, insert, offerReply, local, canFail);
		} else {
			HashMap<Long,RequestTag> map = getRequestTracker(ssk,local);
			innerUnlock(map, (RequestTag)tag, uid, ssk, insert, offerReply, local, canFail);
		}

		synchronized(runningUIDs) {
			UIDTag oldTag = runningUIDs.get(uid);
			if(oldTag == null) {
				if(canFail) return;
				throw new IllegalStateException("Could not unlock "+uid+ "! : ssk="+ssk+" insert="+insert+" canFail="+canFail+" offerReply="+offerReply+" local="+local);
			} else if(tag != oldTag) {
				if(canFail) return;
				Logger.error(this, "Removing "+tag+" for "+uid+" but "+tag+" is registered!");
				return;
			} else {
				runningUIDs.remove(uid);
			}
		}
	}

	private<T extends UIDTag> void innerUnlock(HashMap<Long, T> map, T tag, Long uid, boolean ssk, boolean insert, boolean offerReply, boolean local, boolean canFail) {
		synchronized(map) {
			if(logMINOR) Logger.minor(this, "Unlocking "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+map.size(), new Exception("debug"));
			if(map.get(uid) != tag) {
				if(canFail) {
					if(logMINOR) Logger.minor(this, "Can fail and did fail: removing "+tag+" got "+map.get(uid)+" for "+uid);
				} else {
					Logger.error(this, "Removing "+tag+" for "+uid+" returned "+map.get(uid));
				}
			} else
				map.remove(uid);
			if(logMINOR) Logger.minor(this, "Unlocked "+uid+" ssk="+ssk+" insert="+insert+" offerReply="+offerReply+" local="+local+" size="+map.size());
		}
	}

	private HashMap<Long, RequestTag> getRequestTracker(boolean ssk, boolean local) {
		if(ssk) {
			return local ? runningLocalSSKGetUIDs : runningSSKGetUIDs;
		} else {
			return local ? runningLocalCHKGetUIDs : runningCHKGetUIDs;
		}
	}

	private HashMap<Long, InsertTag> getInsertTracker(boolean ssk, boolean local) {
		if(ssk) {
			return local ? runningLocalSSKPutUIDs : runningSSKPutUIDs;
		} else {
			return local ? runningLocalCHKPutUIDs : runningCHKPutUIDs;
		}
	}

	private HashMap<Long, OfferReplyTag> getOfferTracker(boolean ssk) {
		return ssk ? runningSSKOfferReplyUIDs : runningCHKOfferReplyUIDs;
	}

	static final int TIMEOUT = 10 * 60 * 1000;

	private void startDeadUIDChecker() {
		getTicker().queueTimedJob(deadUIDChecker, TIMEOUT);
	}

	private Runnable deadUIDChecker = new Runnable() {
		public void run() {
			try {
				checkUIDs(runningLocalSSKGetUIDs);
				checkUIDs(runningLocalCHKGetUIDs);
				checkUIDs(runningLocalSSKPutUIDs);
				checkUIDs(runningLocalCHKPutUIDs);
				checkUIDs(runningSSKGetUIDs);
				checkUIDs(runningCHKGetUIDs);
				checkUIDs(runningSSKPutUIDs);
				checkUIDs(runningCHKPutUIDs);
				checkUIDs(runningSSKOfferReplyUIDs);
				checkUIDs(runningCHKOfferReplyUIDs);
			} finally {
				getTicker().queueTimedJob(this, 60*1000);
			}
		}

		private void checkUIDs(HashMap<Long, ? extends UIDTag> map) {
			Long[] uids;
			UIDTag[] tags;
			synchronized(map) {
				uids = map.keySet().toArray(new Long[map.size()]);
				tags = map.values().toArray(new UIDTag[map.size()]);
			}
			long now = System.currentTimeMillis();
			for(int i=0;i<uids.length;i++) {
				if(now - tags[i].createdTime > TIMEOUT) {
					tags[i].logStillPresent(uids[i]);
					synchronized(map) {
						map.remove(uids[i]);
					}
				}
			}
		}
	};


	/**
	 * @return Some status information.
	 */
	public String getStatus() {
		StringBuilder sb = new StringBuilder();
		if (peers != null)
			sb.append(peers.getStatus());
		else
			sb.append("No peers yet");
		sb.append(getNumTransferringRequestSenders());
		sb.append('\n');
		return sb.toString();
	}

	/**
	 * @return TMCI peer list
	 */
	public String getTMCIPeerList() {
		StringBuilder sb = new StringBuilder();
		if (peers != null)
			sb.append(peers.getTMCIPeerList());
		else
			sb.append("No peers yet");
		return sb.toString();
	}

	public int getNumSSKRequests() {
		return runningSSKGetUIDs.size() + runningLocalSSKGetUIDs.size();
	}

	public int getNumCHKRequests() {
		return runningCHKGetUIDs.size() + runningLocalCHKGetUIDs.size();
	}

	public int getNumSSKInserts() {
		return runningSSKPutUIDs.size() + runningLocalSSKPutUIDs.size();
	}

	public int getNumCHKInserts() {
		return runningCHKPutUIDs.size() + runningLocalCHKPutUIDs.size();
	}

	public int getNumLocalSSKRequests() {
		return runningLocalSSKGetUIDs.size();
	}

	public int getNumLocalCHKRequests() {
		return runningLocalCHKGetUIDs.size();
	}

	public int getNumRemoteSSKRequests() {
//		synchronized(runningSSKGetUIDs) {
//			for(Long l : runningSSKGetUIDs)
//				Logger.minor(this, "Running remote SSK fetch: "+l);
//		}
		return runningSSKGetUIDs.size();
	}

	public int getNumRemoteCHKRequests() {
		return runningCHKGetUIDs.size();
	}

	public int getNumLocalSSKInserts() {
		return runningLocalSSKPutUIDs.size();
	}

	public int getNumLocalCHKInserts() {
		return runningLocalCHKPutUIDs.size();
	}

	public int getNumRemoteSSKInserts() {
		return runningSSKPutUIDs.size();
	}

	public int getNumRemoteCHKInserts() {
		return runningCHKPutUIDs.size();
	}

	public int getNumSSKOfferReplies() {
		return runningSSKOfferReplyUIDs.size();
	}

	public int getNumCHKOfferReplies() {
		return runningCHKOfferReplyUIDs.size();
	}

	public int getNumTransferringRequestSenders() {
		synchronized(transferringRequestSenders) {
			return transferringRequestSenders.size();
		}
	}

	public int getNumTransferringRequestHandlers() {
		synchronized(transferringRequestHandlers) {
			return transferringRequestHandlers.size();
		}
	}

	/**
	 * @return Data String for freeviz.
	 */
	public String getFreevizOutput() {
		StringBuilder sb = new StringBuilder();

		sb.append("\ntransferring_requests=");
		sb.append(getNumTransferringRequestSenders());

		sb.append('\n');

		if (peers != null)
			sb.append(peers.getFreevizOutput());

		return sb.toString();
	}

	final LRUQueue<Long> recentlyCompletedIDs;

	static final int MAX_RECENTLY_COMPLETED_IDS = 10*1000;
	/** Length of signature parameters R and S */
	static final int SIGNATURE_PARAMETER_LENGTH = 32;

	/**
	 * Has a request completed with this ID recently?
	 */
	public boolean recentlyCompleted(long id) {
		synchronized (recentlyCompletedIDs) {
			return recentlyCompletedIDs.contains(id);
		}
	}

	/**
	 * A request completed (regardless of success).
	 */
	void completed(long id) {
		synchronized (recentlyCompletedIDs) {
			recentlyCompletedIDs.push(id);
			while(recentlyCompletedIDs.size() > MAX_RECENTLY_COMPLETED_IDS)
				recentlyCompletedIDs.pop();
		}
	}

	public boolean isTestnetEnabled() {
		return testnetEnabled;
	}

	public ClientKeyBlock fetchKey(ClientKey key, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore) throws KeyVerifyException {
		if(key instanceof ClientCHK)
			return fetch((ClientCHK)key, canReadClientCache, canWriteClientCache, canWriteDatastore);
		else if(key instanceof ClientSSK)
			return fetch((ClientSSK)key, canReadClientCache, canWriteClientCache, canWriteDatastore);
		else
			throw new IllegalStateException("Don't know what to do with "+key);
	}

	public ClientKeyBlock fetch(ClientSSK clientSSK, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore) throws SSKVerifyException {
		DSAPublicKey key = clientSSK.getPubKey();
		if(key == null) {
			key = getPubKey.getKey(clientSSK.pubKeyHash, canReadClientCache, false, null);
		}
		if(key == null) return null;
		clientSSK.setPublicKey(key);
		SSKBlock block = fetch((NodeSSK)clientSSK.getNodeKey(true), false, canReadClientCache, canWriteClientCache, canWriteDatastore, false, null);
		if(block == null) {
			if(logMINOR)
				Logger.minor(this, "Could not find key for "+clientSSK);
			return null;
		}
		// Move the pubkey to the top of the LRU, and fix it if it
		// was corrupt.
		getPubKey.cacheKey(clientSSK.pubKeyHash, key, false, canWriteClientCache, canWriteDatastore, false, writeLocalToDatastore);
		return ClientSSKBlock.construct(block, clientSSK);
	}

	private ClientKeyBlock fetch(ClientCHK clientCHK, boolean canReadClientCache, boolean canWriteClientCache, boolean canWriteDatastore) throws CHKVerifyException {
		CHKBlock block = fetch(clientCHK.getNodeCHK(), false, canReadClientCache, canWriteClientCache, canWriteDatastore, false, null);
		if(block == null) return null;
		return new ClientCHKBlock(block, clientCHK);
	}

	public void exit(int reason) {
		try {
			this.park();
			System.out.println("Goodbye.");
			System.out.println(reason);
		} finally {
			System.exit(reason);
		}
	}

	public void exit(String reason){
		try {
			this.park();
			System.out.println("Goodbye. from "+this+" ("+reason+ ')');
		} finally {
			System.exit(0);
		}
	}

	/**
	 * Returns true if the node is shutting down.
	 * The packet receiver calls this for every packet, and boolean is atomic, so this method is not synchronized.
	 */
	public boolean isStopping() {
		return isStopping;
	}

	/**
	 * Get the node into a state where it can be stopped safely
	 * May be called twice - once in exit (above) and then again
	 * from the wrapper triggered by calling System.exit(). Beware!
	 */
	public void park() {
		synchronized(this) {
			if(isStopping) return;
			isStopping = true;
		}

		try {
			Message msg = DMT.createFNPDisconnect(false, false, -1, new ShortBuffer(new byte[0]));
			peers.localBroadcast(msg, true, false, peers.ctrDisconn);
		} catch (Throwable t) {
			try {
				// E.g. if we haven't finished startup
				Logger.error(this, "Failed to tell peers we are going down: "+t, t);
			} catch (Throwable t1) {
				// Ignore. We don't want to mess up the exit process!
			}
		}

		config.store();

		// TODO: find a smarter way of doing it not involving any casting
		Yarrow myRandom = (Yarrow) random;
		myRandom.write_seed(myRandom.seedfile, true);
	}

	public NodeUpdateManager getNodeUpdater(){
		return nodeUpdater;
	}

	public DarknetPeerNode[] getDarknetConnections() {
		return peers.getDarknetPeers();
	}

	public boolean addPeerConnection(PeerNode pn) {
		boolean retval = peers.addPeer(pn);
		peers.writePeers();
		return retval;
	}

	public void removePeerConnection(PeerNode pn) {
		peers.disconnect(pn, true, false, false);
	}

	public void onConnectedPeer() {
		if(logMINOR) Logger.minor(this, "onConnectedPeer()");
		ipDetector.onConnectedPeer();
	}

	public int getFNPPort(){
		return this.getDarknetPortNumber();
	}

	public synchronized boolean setNewestPeerLastGoodVersion( int version ) {
		if( version > buildOldAgeUserAlert.lastGoodVersion ) {
			if( buildOldAgeUserAlert.lastGoodVersion == 0 ) {
				clientCore.alerts.register(buildOldAgeUserAlert);
			}
			buildOldAgeUserAlert.lastGoodVersion = version;
			return true;
		}
		return false;
	}

	public synchronized boolean isOudated() {
		return (buildOldAgeUserAlert.lastGoodVersion > 0);
	}

	private Map<Integer, NodeToNodeMessageListener> n2nmListeners = new HashMap<Integer, NodeToNodeMessageListener>();

	public synchronized void registerNodeToNodeMessageListener(int type, NodeToNodeMessageListener listener) {
		n2nmListeners.put(type, listener);
	}

	/**
	 * Handle a received node to node message
	 */
	public void receivedNodeToNodeMessage(Message m, PeerNode src) {
		int type = ((Integer) m.getObject(DMT.NODE_TO_NODE_MESSAGE_TYPE)).intValue();
		ShortBuffer messageData = (ShortBuffer) m.getObject(DMT.NODE_TO_NODE_MESSAGE_DATA);
		receivedNodeToNodeMessage(src, type, messageData, false);
	}

	public void receivedNodeToNodeMessage(PeerNode src, int type, ShortBuffer messageData, boolean partingMessage) {
		boolean fromDarknet = src instanceof DarknetPeerNode;

		NodeToNodeMessageListener listener = null;
		synchronized(this) {
			listener = n2nmListeners.get(type);
		}

		if(listener == null) {
			Logger.error(this, "Unknown n2nm ID: "+type+" - discarding packet length "+messageData.getLength());
			return;
		}

		listener.handleMessage(messageData.getData(), fromDarknet, src, type);
	}

	private NodeToNodeMessageListener diffNoderefListener = new NodeToNodeMessageListener() {

		public void handleMessage(byte[] data, boolean fromDarknet, PeerNode src, int type) {
			Logger.normal(this, "Received differential node reference node to node message from "+src.getPeer());
			SimpleFieldSet fs = null;
			try {
				fs = new SimpleFieldSet(new String(data, "UTF-8"), false, true);
			} catch (IOException e) {
				Logger.error(this, "IOException while parsing node to node message data", e);
				return;
			}
			if(fs.get("n2nType") != null) {
				fs.removeValue("n2nType");
			}
			try {
				src.processDiffNoderef(fs);
			} catch (FSParseException e) {
				Logger.error(this, "FSParseException while parsing node to node message data", e);
				return;
			}
		}

	};

	private NodeToNodeMessageListener fproxyN2NMListener = new NodeToNodeMessageListener() {

		public void handleMessage(byte[] data, boolean fromDarknet, PeerNode src, int type) {
			if(!fromDarknet) {
				Logger.error(this, "Got N2NTM from non-darknet node ?!?!?!: from "+src);
				return;
			}
			DarknetPeerNode darkSource = (DarknetPeerNode) src;
			Logger.normal(this, "Received N2NTM from '"+darkSource.getPeer()+"'");
			SimpleFieldSet fs = null;
			try {
				fs = new SimpleFieldSet(new String(data, "UTF-8"), false, true);
			} catch (UnsupportedEncodingException e) {
				throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
			} catch (IOException e) {
				Logger.error(this, "IOException while parsing node to node message data", e);
				return;
			}
			fs.putOverwrite("n2nType", Integer.toString(type));
			fs.putOverwrite("receivedTime", Long.toString(System.currentTimeMillis()));
			fs.putOverwrite("receivedAs", "nodeToNodeMessage");
			int fileNumber = darkSource.writeNewExtraPeerDataFile( fs, EXTRA_PEER_DATA_TYPE_N2NTM);
			if( fileNumber == -1 ) {
				Logger.error( this, "Failed to write N2NTM to extra peer data file for peer "+darkSource.getPeer());
			}
			// Keep track of the fileNumber so we can potentially delete the extra peer data file later, the file is authoritative
			try {
				handleNodeToNodeTextMessageSimpleFieldSet(fs, darkSource, fileNumber);
			} catch (FSParseException e) {
				// Shouldn't happen
				throw new Error(e);
			}
		}

	};

	/**
	 * Handle a node to node text message SimpleFieldSet
	 * @throws FSParseException
	 */
	public void handleNodeToNodeTextMessageSimpleFieldSet(SimpleFieldSet fs, DarknetPeerNode source, int fileNumber) throws FSParseException {
		if(logMINOR)
			Logger.minor(this, "Got node to node message: \n"+fs);
		int overallType = fs.getInt("n2nType");
		fs.removeValue("n2nType");
		if(overallType == Node.N2N_MESSAGE_TYPE_FPROXY) {
			handleFproxyNodeToNodeTextMessageSimpleFieldSet(fs, source, fileNumber);
		} else {
			Logger.error(this, "Received unknown node to node message type '"+overallType+"' from "+source.getPeer());
		}
	}

	private void handleFproxyNodeToNodeTextMessageSimpleFieldSet(SimpleFieldSet fs, DarknetPeerNode source, int fileNumber) throws FSParseException {
		int type = fs.getInt("type");
		if(type == Node.N2N_TEXT_MESSAGE_TYPE_USERALERT) {
			source.handleFproxyN2NTM(fs, fileNumber);
		} else if(type == Node.N2N_TEXT_MESSAGE_TYPE_FILE_OFFER) {
			source.handleFproxyFileOffer(fs, fileNumber);
		} else if(type == Node.N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_ACCEPTED) {
			source.handleFproxyFileOfferAccepted(fs, fileNumber);
		} else if(type == Node.N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_REJECTED) {
			source.handleFproxyFileOfferRejected(fs, fileNumber);
		} else if(type == Node.N2N_TEXT_MESSAGE_TYPE_BOOKMARK) {
			source.handleFproxyBookmarkFeed(fs, fileNumber);
		} else if(type == Node.N2N_TEXT_MESSAGE_TYPE_DOWNLOAD) {
			source.handleFproxyDownloadFeed(fs, fileNumber);
		} else {
			Logger.error(this, "Received unknown fproxy node to node message sub-type '"+type+"' from "+source.getPeer());
		}
	}

	public String getMyName() {
		return myName;
	}

	public MessageCore getUSM() {
		return usm;
	}

	public LocationManager getLocationManager() {
		return lm;
	}

	public int getSwaps() {
		return LocationManager.swaps;
	}

	public int getNoSwaps() {
		return LocationManager.noSwaps;
	}

	public int getStartedSwaps() {
		return LocationManager.startedSwaps;
	}

	public int getSwapsRejectedAlreadyLocked() {
		return LocationManager.swapsRejectedAlreadyLocked;
	}

	public int getSwapsRejectedNowhereToGo() {
		return LocationManager.swapsRejectedNowhereToGo;
	}

	public int getSwapsRejectedRateLimit() {
		return LocationManager.swapsRejectedRateLimit;
	}

	public int getSwapsRejectedRecognizedID() {
		return LocationManager.swapsRejectedRecognizedID;
	}

	public PeerNode[] getPeerNodes() {
		return peers.myPeers;
	}

	public PeerNode[] getConnectedPeers() {
		return peers.connectedPeers;
	}

	/**
	 * Return a peer of the node given its ip and port, name or identity, as a String
	 */
	public PeerNode getPeerNode(String nodeIdentifier) {
		PeerNode[] pn = peers.myPeers;
		for(int i=0;i<pn.length;i++)
		{
			Peer peer = pn[i].getPeer();
			String nodeIpAndPort = "";
			if(peer != null) {
				nodeIpAndPort = peer.toString();
			}
			String identity = pn[i].getIdentityString();
			if(pn[i] instanceof DarknetPeerNode) {
				DarknetPeerNode dpn = (DarknetPeerNode) pn[i];
				String name = dpn.myName;
				if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier) || name.equals(nodeIdentifier)) {
					return pn[i];
				}
			} else {
				if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier)) {
					return pn[i];
				}
			}
		}
		return null;
	}

	public boolean isHasStarted() {
		return hasStarted;
	}

	public void queueRandomReinsert(KeyBlock block) {
		clientCore.queueRandomReinsert(block);
	}

	public String getExtraPeerDataDir() {
		return extraPeerDataDir.getPath();
	}

	public boolean noConnectedPeers() {
		return !peers.anyConnectedPeers();
	}

	public double getLocation() {
		return lm.getLocation();
	}

	public double getLocationChangeSession() {
		return lm.getLocChangeSession();
	}

	public int getAverageOutgoingSwapTime() {
		return lm.getAverageSwapTime();
	}

	public int getSendSwapInterval() {
		return lm.getSendSwapInterval();
	}

	public int getNumberOfRemotePeerLocationsSeenInSwaps() {
		return lm.numberOfRemotePeerLocationsSeenInSwaps;
	}

	public boolean isAdvancedModeEnabled() {
		if(clientCore == null) return false;
		return clientCore.isAdvancedModeEnabled();
	}

	public boolean isFProxyJavascriptEnabled() {
		return clientCore.isFProxyJavascriptEnabled();
	}

	// FIXME convert these kind of threads to Checkpointed's and implement a handler
	// using the PacketSender/Ticker. Would save a few threads.

	public int getNumARKFetchers() {
		PeerNode[] p = peers.myPeers;
		int x = 0;
		for(int i=0;i<p.length;i++) {
			if(p[i].isFetchingARK()) x++;
		}
		return x;
	}

	// FIXME put this somewhere else
	private volatile Object statsSync = new Object();

	/** The total number of bytes of real data i.e.&nbsp;payload sent by the node */
	private long totalPayloadSent;

	public void sentPayload(int len) {
		synchronized(statsSync) {
			totalPayloadSent += len;
		}
	}

	/**
	 * Get the total number of bytes of payload (real data) sent by the node
	 *
	 * @return Total payload sent in bytes
	 */
	public long getTotalPayloadSent() {
		synchronized(statsSync) {
			return totalPayloadSent;
		}
	}

	public void setName(String key) throws InvalidConfigValueException, NodeNeedRestartException {
		 config.get("node").getOption("name").setValue(key);
	}

	public Ticker getTicker() {
		return ps;
	}

	public int getUnclaimedFIFOSize() {
		return usm.getUnclaimedFIFOSize();
	}

	/**
	 * Connect this node to another node (for purposes of testing)
	 */
	public void connectToSeednode(SeedServerTestPeerNode node) throws OpennetDisabledException, FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		peers.addPeer(node,false,false);
	}
	public void connect(Node node) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		peers.connect(node.darknetCrypto.exportPublicFieldSet(), darknetCrypto.packetMangler);
	}

	public short maxHTL() {
		return maxHTL;
	}

	public int getDarknetPortNumber() {
		return darknetCrypto.portNumber;
	}

	public int getOutputBandwidthLimit() {
		return outputBandwidthLimit;
	}

	public synchronized int getInputBandwidthLimit() {
		if(inputLimitDefault)
			return outputBandwidthLimit * 4;
		return inputBandwidthLimit;
	}

	public synchronized void setTimeSkewDetectedUserAlert() {
		if(timeSkewDetectedUserAlert == null) {
			timeSkewDetectedUserAlert = new TimeSkewDetectedUserAlert();
			clientCore.alerts.register(timeSkewDetectedUserAlert);
		}
	}

	public File getNodeDir() {
		return nodeDir;
	}

	public DarknetPeerNode createNewDarknetNode(SimpleFieldSet fs) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		return new DarknetPeerNode(fs, this, darknetCrypto, peers, false, darknetCrypto.packetMangler);
	}

	public OpennetPeerNode createNewOpennetNode(SimpleFieldSet fs) throws FSParseException, OpennetDisabledException, PeerParseException, ReferenceSignatureVerificationException {
		if(opennet == null) throw new OpennetDisabledException("Opennet is not currently enabled");
		return new OpennetPeerNode(fs, this, opennet.crypto, opennet, peers, false, opennet.crypto.packetMangler);
	}

	public SeedServerTestPeerNode createNewSeedServerTestPeerNode(SimpleFieldSet fs) throws FSParseException, OpennetDisabledException, PeerParseException, ReferenceSignatureVerificationException {
		if(opennet == null) throw new OpennetDisabledException("Opennet is not currently enabled");
		return new SeedServerTestPeerNode(fs, this, opennet.crypto, peers, true, opennet.crypto.packetMangler);
	}

	public OpennetPeerNode addNewOpennetNode(SimpleFieldSet fs, ConnectionType connectionType) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		// FIXME: perhaps this should throw OpennetDisabledExcemption rather than returing false?
		if(opennet == null) return null;
		return opennet.addNewOpennetNode(fs, connectionType);
	}

	public byte[] getOpennetIdentity() {
		return opennet.crypto.myIdentity;
	}

	public byte[] getDarknetIdentity() {
		return darknetCrypto.myIdentity;
	}

	public int estimateFullHeadersLengthOneMessage() {
		return darknetCrypto.packetMangler.fullHeadersLengthOneMessage();
	}

	public synchronized boolean isOpennetEnabled() {
		return opennet != null;
	}

	public SimpleFieldSet exportDarknetPublicFieldSet() {
		return darknetCrypto.exportPublicFieldSet();
	}

	public SimpleFieldSet exportOpennetPublicFieldSet() {
		return opennet.crypto.exportPublicFieldSet();
	}

	public SimpleFieldSet exportDarknetPrivateFieldSet() {
		return darknetCrypto.exportPrivateFieldSet();
	}

	public SimpleFieldSet exportOpennetPrivateFieldSet() {
		return opennet.crypto.exportPrivateFieldSet();
	}

	/**
	 * Should the IP detection code only use the IP address override and the bindTo information,
	 * rather than doing a full detection?
	 */
	public synchronized boolean dontDetect() {
		// Only return true if bindTo is set on all ports which are in use
		if(!darknetCrypto.getBindTo().isRealInternetAddress(false, true, false)) return false;
		if(opennet != null) {
			if(opennet.crypto.getBindTo().isRealInternetAddress(false, true, false)) return false;
		}
		return true;
	}

	public int getOpennetFNPPort() {
		if(opennet == null) return -1;
		return opennet.crypto.portNumber;
	}

	public OpennetManager getOpennet() {
		return opennet;
	}

	public synchronized boolean passOpennetRefsThroughDarknet() {
		return passOpennetRefsThroughDarknet;
	}

	/**
	 * Get the set of public ports that need to be forwarded. These are internal
	 * ports, not necessarily external - they may be rewritten by the NAT.
	 * @return A Set of ForwardPort's to be fed to port forward plugins.
	 */
	public Set<ForwardPort> getPublicInterfacePorts() {
		HashSet<ForwardPort> set = new HashSet<ForwardPort>();
		// FIXME IPv6 support
		set.add(new ForwardPort("darknet", false, ForwardPort.PROTOCOL_UDP_IPV4, darknetCrypto.portNumber));
		if(opennet != null) {
			NodeCrypto crypto = opennet.crypto;
			if(crypto != null) {
				set.add(new ForwardPort("opennet", false, ForwardPort.PROTOCOL_UDP_IPV4, crypto.portNumber));
			}
		}
		return set;
	}

	/**
	 * Get the time since the node was started in milliseconds.
	 *
	 * @return Uptime in milliseconds
	 */
	public long getUptime() {
		return System.currentTimeMillis() - usm.getStartedTime();
	}

	public synchronized UdpSocketHandler[] getPacketSocketHandlers() {
		// FIXME better way to get these!
		if(opennet != null) {
			return new UdpSocketHandler[] { darknetCrypto.socket, opennet.crypto.socket };
			// TODO Auto-generated method stub
		} else {
			return new UdpSocketHandler[] { darknetCrypto.socket };
		}
	}

	public int getMaxOpennetPeers() {
		return maxOpennetPeers;
	}

	public void onAddedValidIP() {
		OpennetManager om;
		synchronized(this) {
			om = opennet;
		}
		if(om != null) {
			Announcer announcer = om.announcer;
			if(announcer != null) {
				announcer.maybeSendAnnouncement();
			}
		}
	}

	/**
	 * Returns true if the packet receiver should try to decode/process packets that are not from a peer (i.e. from a seed connection)
	 * The packet receiver calls this upon receiving an unrecognized packet.
	 */
	public boolean wantAnonAuth() {
		return opennet != null && acceptSeedConnections;
	}

	public boolean opennetDefinitelyPortForwarded() {
		OpennetManager om;
		synchronized(this) {
			om = this.opennet;
		}
		if(om == null) return false;
		NodeCrypto crypto = om.crypto;
		if(crypto == null) return false;
		return crypto.definitelyPortForwarded();
	}

	public boolean darknetDefinitelyPortForwarded() {
		if(darknetCrypto == null) return false;
		return darknetCrypto.definitelyPortForwarded();
	}

	public boolean hasKey(Key key, boolean canReadClientCache, boolean forULPR) {
		// FIXME optimise!
		if(key instanceof NodeCHK)
			return fetch((NodeCHK)key, true, canReadClientCache, false, false, forULPR, null) != null;
		else
			return fetch((NodeSSK)key, true, canReadClientCache, false, false, forULPR, null) != null;
	}

	public int getTotalRunningUIDs() {
		synchronized(runningUIDs) {
			return runningUIDs.size();
		}
	}

	public void addRunningUIDs(Vector<Long> list) {
		synchronized(runningUIDs) {
			list.addAll(runningUIDs.keySet());
		}
	}

	public int getTotalRunningUIDsAlt() {
		synchronized(runningUIDs) {
			return this.runningCHKGetUIDs.size() + this.runningCHKPutUIDs.size() + this.runningSSKGetUIDs.size() +
			this.runningSSKGetUIDs.size() + this.runningSSKOfferReplyUIDs.size() + this.runningCHKOfferReplyUIDs.size();
		}
	}

	/**
	 * Warning: does not announce change in location!
	 */
	public void setLocation(double loc) {
		lm.setLocation(loc);
	}

	public boolean peersWantKey(Key key) {
		return failureTable.peersWantKey(key);
	}

	private SimpleUserAlert alertMTUTooSmall;

	public final RequestClient nonPersistentClient = new RequestClient() {
		public boolean persistent() {
			return false;
		}
		public void removeFrom(ObjectContainer container) {
			throw new UnsupportedOperationException();
		}
	};

	public void onTooLowMTU(int minAdvertisedMTU, int minAcceptableMTU) {
		if(alertMTUTooSmall == null) {
			alertMTUTooSmall = new SimpleUserAlert(false, l10n("tooSmallMTU"), l10n("tooSmallMTULong", new String[] { "mtu", "minMTU" }, new String[] { Integer.toString(minAdvertisedMTU), Integer.toString(minAcceptableMTU) }), l10n("tooSmallMTUShort"), UserAlert.ERROR);
		} else return;
		clientCore.alerts.register(alertMTUTooSmall);
	}

	public void setDispatcherHook(NodeDispatcherCallback cb) {
		this.dispatcher.setHook(cb);
	}

	public boolean shallWePublishOurPeersLocation() {
		return publishOurPeersLocation;
	}

	public boolean shallWeRouteAccordingToOurPeersLocation() {
		return routeAccordingToOurPeersLocation && Version.lastGoodBuild() >= 1160;
	}

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing Node in database", new Exception("error"));
		return false;
	}

	private volatile long turtleCount;

	/**
	 * Make a running request sender into a turtle request.
	 * Backoff: when the transfer finishes, or after 10 seconds if no cancellation.
	 * Downstream: Cancel all dependant RequestHandler's and local requests.
	 * This also removes it from the load management code.
	 * Registration: We track the turtles for each peer, and overall. No two turtles from the
	 * same node may share the same key, and there is an overall limit.
	 * @param sender
	 */
	public void makeTurtle(RequestSender sender) {
		// Registration
		// FIXME check the datastore.
		if(!this.registerTurtleTransfer(sender)) {
			// Too many turtles running, or already two turtles for this key (we allow two in case one peer turtles as a DoS).
			sender.killTurtle();
			Logger.error(this, "Didn't make turtle (global) for key "+sender.key+" for "+sender);
			return;
		}
		PeerNode from = sender.transferringFrom();
		if(from == null) {
			// Race condition, it has finished, avoid NPE
			return;
		}
		if(!from.registerTurtleTransfer(sender)) {
			// Too many turtles running, or already a turtle for this key.
			// Abort it.
			unregisterTurtleTransfer(sender);
			sender.killTurtle();
			Logger.error(this, "Didn't make turtle (peer) for key "+sender.key+" for "+sender);
			return;
		}
		Logger.normal(this, "TURTLING: "+sender.key+" for "+sender);
		// Do not transfer coalesce!!
		synchronized(transferringRequestSenders) {
			transferringRequestSenders.remove(sender.key);
		}
		turtleCount++;

		// Abort downstream transfers, set the turtle mode flag and set up the backoff callback.
		sender.setTurtle();
	}

	public long getTurtleCount() {
		return turtleCount;
	}

	private static int MAX_TURTLES = 10;
	private static int MAX_TURTLES_PER_KEY = 2;

	private HashMap<Key,RequestSender[]> turtlingTransfers = new HashMap<Key,RequestSender[]>();

	private boolean registerTurtleTransfer(RequestSender sender) {
		Key key = sender.key;
		synchronized(turtlingTransfers) {
			if(getNumIncomingTurtles() >= MAX_TURTLES) {
				Logger.error(this, "Too many turtles running globally");
				return false;
			}
			if(!turtlingTransfers.containsKey(key)) {
				turtlingTransfers.put(key, new RequestSender[] { sender });
				Logger.normal(this, "Running turtles (a): "+getNumIncomingTurtles()+" : "+turtlingTransfers.size());
				return true;
			} else {
				RequestSender[] senders = turtlingTransfers.get(key);
				if(senders.length >= MAX_TURTLES_PER_KEY) {
					Logger.error(this, "Too many turtles for key globally");
					return false;
				}
				for(int i=0;i<senders.length;i++) {
					if(senders[i] == sender) {
						Logger.error(this, "Registering turtle for "+sender+" : "+key+" twice! (globally)");
						return false;
					}
				}
				RequestSender[] newSenders = new RequestSender[senders.length+1];
				System.arraycopy(senders, 0, newSenders, 0, senders.length);
				newSenders[senders.length] = sender;
				turtlingTransfers.put(key, newSenders);
				Logger.normal(this, "Running turtles (b): "+getNumIncomingTurtles()+" : "+turtlingTransfers.size());
				return true;
			}
		}
	}

	public void unregisterTurtleTransfer(RequestSender sender) {
		Key key = sender.key;
		synchronized(turtlingTransfers) {
			if(!turtlingTransfers.containsKey(key)) {
				Logger.error(this, "Removing turtle "+sender+" for "+key+" : DOES NOT EXIST IN GLOBAL TURTLES LIST");
				return;
			}
			RequestSender[] senders = turtlingTransfers.get(key);
			if(senders.length == 1 && senders[0] == sender) {
				turtlingTransfers.remove(key);
				return;
			}
			if(senders.length == 2) {
				if(senders[0] == sender) {
					turtlingTransfers.put(key, new RequestSender[] { senders[1] });
				} else if(senders[1] == sender) {
					turtlingTransfers.put(key, new RequestSender[] { senders[0] });
				}
				return;
			}
			int x = 0;
			for(int i=0;i<senders.length;i++) {
				if(senders[i] == sender) x++;
			}
			if(x == 0) {
				Logger.error(this, "Turtle not in global register: "+sender+" for "+key);
				return;
			}
			if(senders.length == x) {
				Logger.error(this, "Lots of copies of turtle: "+x);
				turtlingTransfers.remove(key);
				return;
			}
			RequestSender[] newSenders = new RequestSender[senders.length - x];
			int idx = 0;
			for(RequestSender s : senders) {
				if(s == sender) continue;
				newSenders[idx++] = s;
			}
			turtlingTransfers.put(key, newSenders);
		}
	}

	public int getNumIncomingTurtles() {
		synchronized(turtlingTransfers) {
			int turtles = 0;
			for(RequestSender[] senders : turtlingTransfers.values())
				turtles += senders.length;
			return turtles;
		}
	}

	public void drawClientCacheBox(HTMLNode storeSizeInfobox) {
		HTMLNode div = storeSizeInfobox.addChild("div");
		div.addChild("p", "Client cache max size: "+this.maxClientCacheKeys+" keys");
		div.addChild("p", "Client cache size: CHK "+this.chkClientcache.keyCount()+" pubkey "+this.pubKeyClientcache.keyCount()+" SSK "+this.sskClientcache.keyCount());
		div.addChild("p", "Client cache misses: CHK "+this.chkClientcache.misses()+" pubkey "+this.pubKeyClientcache.misses()+" SSK "+this.sskClientcache.misses());
		div.addChild("p", "Client cache hits: CHK "+this.chkClientcache.hits()+" pubkey "+this.pubKeyClientcache.hits()+" SSK "+this.sskClientcache.hits());
	}

	public void drawSlashdotCacheBox(HTMLNode storeSizeInfobox) {
		HTMLNode div = storeSizeInfobox.addChild("div");
		div.addChild("p", "Slashdot/ULPR cache max size: "+maxSlashdotCacheKeys+" keys");
		div.addChild("p", "Slashdot/ULPR cache size: CHK "+this.chkSlashdotcache.keyCount()+" pubkey "+this.pubKeySlashdotcache.keyCount()+" SSK "+this.sskSlashdotcache.keyCount());
		div.addChild("p", "Slashdot/ULPR cache misses: CHK "+this.chkSlashdotcache.misses()+" pubkey "+this.pubKeySlashdotcache.misses()+" SSK "+this.sskSlashdotcache.misses());
		div.addChild("p", "Slashdot/ULPR cache hits: CHK "+this.chkSlashdotcache.hits()+" pubkey "+this.pubKeySlashdotcache.hits()+" SSK "+this.sskSlashdotcache.hits());
		div.addChild("p", "Slashdot/ULPR cache writes: CHK "+this.chkSlashdotcache.writes()+" pubkey "+this.pubKeySlashdotcache.writes()+" SSK "+this.sskSlashdotcache.writes());
	}

	private boolean enteredPassword;

	public void setMasterPassword(String password, boolean inFirstTimeWizard) throws AlreadySetPasswordException, MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
		synchronized(this) {
			if(enteredPassword)
				throw new AlreadySetPasswordException();
		}
		if(securityLevels.getPhysicalThreatLevel() == PHYSICAL_THREAT_LEVEL.MAXIMUM)
			Logger.error(this, "Setting password while physical threat level is at MAXIMUM???");
		MasterKeys keys = MasterKeys.read(masterKeysFile, random, password);
		try {
			setPasswordInner(keys, inFirstTimeWizard);
		} finally {
			keys.clearAll();
		}
	}

	private void setPasswordInner(MasterKeys keys, boolean inFirstTimeWizard) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException {
		boolean wantClientCache = false;
		boolean wantDatabase = false;
		synchronized(this) {
			enteredPassword = true;
			if(!clientCacheAwaitingPassword) {
				if(inFirstTimeWizard) {
					byte[] copied = new byte[keys.clientCacheMasterKey.length];
					System.arraycopy(keys.clientCacheMasterKey, 0, copied, 0, copied.length);
					cachedClientCacheKey = copied;
					// Wipe it if haven't specified datastore size in 10 minutes.
					ps.queueTimedJob(new Runnable() {
						public void run() {
							synchronized(Node.this) {
								MasterKeys.clear(cachedClientCacheKey);
								cachedClientCacheKey = null;
							}
						}

					}, 10*60*1000);
				}
			} else wantClientCache = true;
			wantDatabase = db == null;
		}
		if(wantClientCache)
			activatePasswordedClientCache(keys);
		if(wantDatabase)
			lateSetupDatabase(keys.databaseKey);
	}


	private void activatePasswordedClientCache(MasterKeys keys) {
		synchronized(this) {
			if(clientCacheType.equals("ram")) {
				System.err.println("RAM client cache cannot be passworded!");
				return;
			}
			if(!clientCacheType.equals("salt-hash")) {
				System.err.println("Unknown client cache type, cannot activate passworded store: "+clientCacheType);
				return;
			}
		}
		Runnable migrate = new MigrateOldStoreData(true);
		String suffix = getStoreSuffix();
		try {
			initSaltHashClientCacheFS(suffix, true, keys.clientCacheMasterKey);
		} catch (NodeInitException e) {
			Logger.error(this, "Unable to activate passworded client cache", e);
			System.err.println("Unable to activate passworded client cache: "+e);
			e.printStackTrace();
			return;
		}

		synchronized(this) {
			clientCacheAwaitingPassword = false;
		}

		executor.execute(migrate, "Migrate data from previous store");
	}

	public void changeMasterPassword(String oldPassword, String newPassword, boolean inFirstTimeWizard) throws MasterKeysWrongPasswordException, MasterKeysFileSizeException, IOException, AlreadySetPasswordException {
		if(securityLevels.getPhysicalThreatLevel() == PHYSICAL_THREAT_LEVEL.MAXIMUM)
			Logger.error(this, "Changing password while physical threat level is at MAXIMUM???");
		if(masterKeysFile.exists()) {
			MasterKeys keys = MasterKeys.read(masterKeysFile, random, oldPassword);
			keys.changePassword(masterKeysFile, newPassword, random);
			setPasswordInner(keys, inFirstTimeWizard);
			keys.clearAll();
		} else {
			setMasterPassword(newPassword, inFirstTimeWizard);
		}
	}

	public static class AlreadySetPasswordException extends Exception {

	   final private static long serialVersionUID = -7328456475029374032L;

	}

	public synchronized File getMasterPasswordFile() {
		return masterKeysFile;
	}

	public void panic() {
		try {
			db.close();
		} catch (Throwable t) {
			// Ignore
		}
		synchronized(this) {
			db = null;
		}
		try {
			FileUtil.secureDelete(dbFile, random);
			FileUtil.secureDelete(dbFileCrypt, random);
		} catch (IOException e) {
			// Ignore
		}
		dbFile.delete();
		dbFileCrypt.delete();
	}

	public void finishPanic() {
		WrapperManager.restart();
		System.exit(0);
	}


	public boolean awaitingPassword() {
		if(clientCacheAwaitingPassword) return true;
		if(databaseAwaitingPassword) return true;
		return false;
	}


	public boolean isDatabaseEncrypted() {
		return databaseEncrypted;
	}

	public boolean hasDatabase() {
		return db != null;
	}


	public synchronized boolean autoChangeDatabaseEncryption() {
		return autoChangeDatabaseEncryption;
	}


	private long completeInsertsStored;
	private long completeInsertsOldStore;
	private long completeInsertsTotal;
	private long completeInsertsNotStoredWouldHaveStored;	// DEBUGGING: should be 0 but can be nonzero if e.g. a request originates from a backed off node; should be very low in any case; FIXME remove eventually

	/** Should we commit the block to the store rather than the cache?
	 *
	 * <p>We used to check whether we are a sink by checking whether any peer has
	 * a closer location than we do. Then we made low-uptime nodes exempt from
	 * this calculation: if we route to a low uptime node with a closer location,
	 * we want to store it anyway since he may go offline. The problem was that
	 * if we routed to a low-uptime node, and there was another option that wasn't
	 * low-uptime but was closer to the target than we were, then we would not
	 * store in the store. Also, routing isn't always by the closest peer location:
	 * FOAF and per-node failure tables change it. So now, we consider the nodes
	 * we have actually routed to:</p>
	 *
	 * <p>Store in datastore if our location is closer to the target than:</p><ol>
	 * <li>the source location (if any, and ignoring if low-uptime)</li>
	 * <li>the locations of the nodes we just routed to (ditto)</li>
	 * </ol>
	 *
	 * @param key
	 * @param source
	 * @param routedTo
	 * @return
	 */
	public boolean shouldStoreDeep(Key key, PeerNode source, PeerNode[] routedTo) {
    	double myLoc = getLocation();
    	double target = key.toNormalizedDouble();
    	double myDist = Location.distance(myLoc, target);

    	boolean wouldHaveStored = !peers.isCloserLocation(target, MIN_UPTIME_STORE_KEY);

    	// First, calculate whether we would have stored it using the old formula.
		if(wouldHaveStored)
			completeInsertsOldStore++;

    	if(logMINOR) Logger.minor(this, "Should store for "+key+" ?");
    	// Don't sink store if any of the nodes we routed to, or our predecessor, is both high-uptime and closer to the target than we are.
    	if(source != null && !source.isLowUptime()) {
    		if(Location.distance(source, target) < myDist) {
    	    	if(logMINOR) Logger.minor(this, "Not storing because source is closer to target for "+key+" : "+source);
    	    	synchronized(this) {
    	    		completeInsertsTotal++;
    	    		if(wouldHaveStored) {
    	    			Logger.error(this, "Would have stored but haven't stored");
    	    			completeInsertsNotStoredWouldHaveStored++;
    	    		}
    	    	}
    			return false;
    		}
    	}
    	for(PeerNode pn : routedTo) {
    		if(Location.distance(pn, target) < myDist && !pn.isLowUptime()) {
    	    	if(logMINOR) Logger.minor(this, "Not storing because peer "+pn+" is closer to target for "+key+" his loc "+pn.getLocation()+" my loc "+myLoc+" target is "+target);
    	    	synchronized(this) {
    	    		completeInsertsTotal++;
    	    		if(wouldHaveStored) {
    	    			Logger.error(this, "Would have stored but haven't stored");
    	    			completeInsertsNotStoredWouldHaveStored++;
    	    		}
    	    	}
    			return false;
    		} else {
    			if(logMINOR) Logger.minor(this, "Should store maybe, peer "+pn+" loc = "+pn.getLocation()+" my loc is "+myLoc+" target is "+target+" low uptime is "+pn.isLowUptime());
    		}
    	}
    	synchronized(this) {
    		completeInsertsStored++;
    		completeInsertsTotal++;
    	}
    	if(logMINOR) Logger.minor(this, "Should store returning true for "+key+" target="+target+" myLoc="+myLoc+" peers: "+routedTo.length);
    	return true;
	}


	private final DecimalFormat fix3p3pct = new DecimalFormat("##0.000%");

	public synchronized void drawStoreStats(HTMLNode infobox) {
		if (completeInsertsTotal != 0) {
			infobox.addChild("p", "Stored inserts: "+completeInsertsStored+" of "+completeInsertsTotal+" ("+fix3p3pct.format((completeInsertsStored*1.0)/completeInsertsTotal)+")");
			infobox.addChild("p", "Would have stored: "+completeInsertsOldStore+" of "+completeInsertsTotal+" ("+fix3p3pct.format((completeInsertsOldStore*1.0)/completeInsertsTotal)+")");
			infobox.addChild("p", "Would have stored but wasn't stored: "+completeInsertsNotStoredWouldHaveStored+" of "+completeInsertsTotal+" ("+fix3p3pct.format((completeInsertsNotStoredWouldHaveStored*1.0)/completeInsertsTotal)+")");
		}
	}
}
