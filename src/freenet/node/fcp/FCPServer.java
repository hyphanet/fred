/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import org.tanukisoftware.wrapper.WrapperManager;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.FetchContext;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertContext;
import freenet.client.async.CacheFetchResult;
import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.async.DownloadCache;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.SSL;
import freenet.io.AllowedHosts;
import freenet.io.NetworkInterface;
import freenet.io.SSLNetworkInterface;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.node.fcp.whiteboard.Whiteboard;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.MutableBoolean;
import freenet.support.OOMHandler;
import freenet.support.api.BooleanCallback;
import freenet.support.api.Bucket;
import freenet.support.api.IntCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;
import freenet.support.io.NoFreeBucket;

/**
 * FCP server process.
 */
public class FCPServer implements Runnable, DownloadCache {

	FCPPersistentRoot persistentRoot;
	private static boolean logMINOR;
	public final static int DEFAULT_FCP_PORT = 9481;
	NetworkInterface networkInterface;
	final NodeClientCore core;
	final Node node;
	final int port;
	private static boolean ssl = false;
	public final boolean enabled;
	String bindTo;
	private String allowedHosts;
	AllowedHosts allowedHostsFullAccess;
	final WeakHashMap<String, FCPClient> rebootClientsByName;
	final FCPClient globalRebootClient;
	FCPClient globalForeverClient;
	final FetchContext defaultFetchContext;
	public InsertContext defaultInsertContext;
	public static final int QUEUE_MAX_RETRIES = -1;
	public static final long QUEUE_MAX_DATA_SIZE = Long.MAX_VALUE;
	private boolean assumeDownloadDDAIsAllowed;
	private boolean assumeUploadDDAIsAllowed;
	private boolean neverDropAMessage;
	private int maxMessageQueueLength;
	private final Whiteboard whiteboard=new Whiteboard();;
	
	public FCPServer(String ipToBindTo, String allowedHosts, String allowedHostsFullAccess, int port, Node node, NodeClientCore core, boolean isEnabled, boolean assumeDDADownloadAllowed, boolean assumeDDAUploadAllowed, boolean neverDropAMessage, int maxMessageQueueLength, ObjectContainer container) throws IOException, InvalidConfigValueException {
		this.bindTo = ipToBindTo;
		this.allowedHosts=allowedHosts;
		this.allowedHostsFullAccess = new AllowedHosts(allowedHostsFullAccess);
		this.port = port;
		this.enabled = isEnabled;
		this.node = node;
		this.core = core;
		this.assumeDownloadDDAIsAllowed = assumeDDADownloadAllowed;
		this.assumeUploadDDAIsAllowed = assumeDDAUploadAllowed;
		this.neverDropAMessage = neverDropAMessage;
		this.maxMessageQueueLength = maxMessageQueueLength;
		rebootClientsByName = new WeakHashMap<String, FCPClient>();
		
		// This one is only used to get the default settings. Individual FCP conns
		// will make their own.
		HighLevelSimpleClient client = core.makeClient((short)0);
		defaultFetchContext = client.getFetchContext();
		defaultInsertContext = client.getInsertContext(false);
		
		globalRebootClient = new FCPClient("Global Queue", null, true, null, ClientRequest.PERSIST_REBOOT, null, whiteboard, null);
		globalRebootClient.setRequestStatusCache(new RequestStatusCache(), null);
		
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		
	}
	
	public void load(ObjectContainer container) {
		persistentRoot = FCPPersistentRoot.create(node.nodeDBHandle, whiteboard, new RequestStatusCache(), container);
		globalForeverClient = persistentRoot.globalForeverClient;
	}
	
	private void maybeGetNetworkInterface() {
		if (this.networkInterface!=null) return;
		
		NetworkInterface tempNetworkInterface = null;
		try {
			if(ssl) {
				tempNetworkInterface = SSLNetworkInterface.create(port, bindTo, allowedHosts, node.executor, true);
			} else {
				tempNetworkInterface = NetworkInterface.create(port, bindTo, allowedHosts, node.executor, true);
			}
		} catch (IOException be) {
			Logger.error(this, "Couldn't bind to FCP Port "+bindTo+ ':' +port+". FCP Server not started.", be);
			System.out.println("Couldn't bind to FCP Port "+bindTo+ ':' +port+". FCP Server not started.");
		}
		
		this.networkInterface = tempNetworkInterface;
		
	}
	
	public void maybeStart() {
		if (this.enabled) {
			maybeGetNetworkInterface();
			
			Logger.normal(this, "Starting FCP server on "+bindTo+ ':' +port+ '.');
			System.out.println("Starting FCP server on "+bindTo+ ':' +port+ '.');
			
			if (this.networkInterface != null) {
				Thread t = new Thread(this, "FCP server");
				t.setDaemon(true);
				t.start();
			}
		} else {
			Logger.normal(this, "Not starting FCP server as it's disabled");
			System.out.println("Not starting FCP server as it's disabled");
			this.networkInterface = null;
		}
	}
	
	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		while(true) {
			try {
				networkInterface.waitBound();
				realRun();
			} catch (IOException e) {
				if(logMINOR) Logger.minor(this, "Caught "+e, e);
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
			if (WrapperManager.hasShutdownHookBeenTriggered())
				return;
			try{
				Thread.sleep(2000);
			}catch (InterruptedException e) {}
		}
	}

	private void realRun() throws IOException {
		if(!node.isHasStarted()) return;
		// Accept a connection
		Socket s = networkInterface.accept();		
		FCPConnectionHandler ch = new FCPConnectionHandler(s, this);
		ch.start();
	}

	static class FCPPortNumberCallback extends IntCallback  {

		private final NodeClientCore node;
		
		FCPPortNumberCallback(NodeClientCore node) {
			this.node = node;
		}
		
		@Override
		public Integer get() {
			return node.getFCPServer().port;
		}

		@Override
		public void set(Integer val) throws InvalidConfigValueException {
			if (!get().equals(val)) {
				throw new InvalidConfigValueException("Cannot change FCP port number on the fly");
			}
		}

		@Override
		public boolean isReadOnly() {
			return true;
		}
	}
	
	static class FCPEnabledCallback extends BooleanCallback {

		final NodeClientCore node;
		
		FCPEnabledCallback(NodeClientCore node) {
			this.node = node;
		}
		
		@Override
		public Boolean get() {
			return node.getFCPServer().enabled;
		}
//TODO: Allow it
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (!get().equals(val)) {
				throw new InvalidConfigValueException(l10n("cannotStartOrStopOnTheFly"));
			}
		}

		@Override
		public boolean isReadOnly() {
			return true;
		}
	}

	static class FCPSSLCallback extends BooleanCallback {

		@Override
		public Boolean get() {
			return ssl;
		}

		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			if(!SSL.available()) {
				throw new InvalidConfigValueException("Enable SSL support before use ssl with FCP");
			}
			ssl = val;
			throw new InvalidConfigValueException("Cannot change SSL on the fly, please restart freenet");
		}

		@Override
		public boolean isReadOnly() {
			return true;
		}
	}

	// FIXME: Consider moving everything except enabled into constructor
	// Actually we could move enabled in too with an exception???
	
	static class FCPBindtoCallback extends StringCallback {

		final NodeClientCore node;
		
		FCPBindtoCallback(NodeClientCore node) {
			this.node = node;
		}
		
		@Override
		public String get() {
			return node.getFCPServer().bindTo;
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			if(!val.equals(get())) {
				try {
					FCPServer server = node.getFCPServer();
					server.networkInterface.setBindTo(val, true);
					server.bindTo = val;
					
					synchronized(server.networkInterface) {
						server.networkInterface.notifyAll();
					}
				} catch (IOException e) {
					// This is an advanced option for reasons of reducing clutter,
					// but it is expected to be used by regular users, not devs.
					// So we translate the error messages.
					throw new InvalidConfigValueException(l10n("couldNotChangeBindTo", "error", e.getLocalizedMessage()));
				}
			}
		}
	}
	
	static class FCPAllowedHostsCallback extends StringCallback  {

		private final NodeClientCore node;
		
		public FCPAllowedHostsCallback(NodeClientCore node) {
			this.node = node;
		}
		
		@Override
		public String get() {
			FCPServer server = node.getFCPServer();
			if(server == null) return NetworkInterface.DEFAULT_BIND_TO;
			NetworkInterface netIface = server.networkInterface;
			return (netIface == null ? NetworkInterface.DEFAULT_BIND_TO : netIface.getAllowedHosts());
		}

		@Override
		public void set(String val) {
			if (!val.equals(get())) {
				node.getFCPServer().networkInterface.setAllowedHosts(val);
			}
		}		
	}

	static class FCPAllowedHostsFullAccessCallback extends StringCallback  {
		private final NodeClientCore node;
		
		public FCPAllowedHostsFullAccessCallback(NodeClientCore node) {
			this.node = node;
		}
		
		@Override
		public String get() {
			return node.getFCPServer().allowedHostsFullAccess.getAllowedHosts();
		}

		@Override
		public void set(String val) {
			if (!val.equals(get())) {
				node.getFCPServer().allowedHostsFullAccess.setAllowedHosts(val);
			}
		}
		
	}
	static class AssumeDDADownloadIsAllowedCallback extends BooleanCallback {
		FCPServer server;

		@Override
		public Boolean get() {
			return server.assumeDownloadDDAIsAllowed;
		}
		
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			server.assumeDownloadDDAIsAllowed = val;
		}
	}
	
	static class AssumeDDAUploadIsAllowedCallback extends BooleanCallback {
		FCPServer server;

		@Override
		public Boolean get() {
			return server.assumeUploadDDAIsAllowed;
		}
		
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			server.assumeUploadDDAIsAllowed = val;
		}
	}
	
	static class NeverDropAMessageCallback extends BooleanCallback {
		FCPServer server;

		@Override
		public Boolean get() {
			return server.neverDropAMessage;
		}
		
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			server.neverDropAMessage = val;
		}
	}
	
	static class MaxMessageQueueLengthCallback extends IntCallback {
		FCPServer server;
		
		@Override
		public Integer get() {
			return server.maxMessageQueueLength;
		}
		
		@Override
		public void set(Integer val) throws InvalidConfigValueException {
			if(get().equals(val))
				return;
			server.maxMessageQueueLength = val;
		}
	}

	
	public static FCPServer maybeCreate(Node node, NodeClientCore core, Config config, ObjectContainer container) throws IOException, InvalidConfigValueException {
		SubConfig fcpConfig = new SubConfig("fcp", config);
		short sortOrder = 0;
		fcpConfig.register("enabled", true, sortOrder++, true, false, "FcpServer.isEnabled", "FcpServer.isEnabledLong", new FCPEnabledCallback(core));
		fcpConfig.register("ssl", false, sortOrder++, true, true, "FcpServer.ssl", "FcpServer.sslLong", new FCPSSLCallback());
		fcpConfig.register("port", FCPServer.DEFAULT_FCP_PORT /* anagram of 1984, and 1000 up from old number */, 2, true, true, "FcpServer.portNumber", "FcpServer.portNumberLong", new FCPPortNumberCallback(core), false);
		fcpConfig.register("bindTo", NetworkInterface.DEFAULT_BIND_TO, sortOrder++, true, true, "FcpServer.bindTo", "FcpServer.bindToLong", new FCPBindtoCallback(core));
		fcpConfig.register("allowedHosts", NetworkInterface.DEFAULT_BIND_TO, sortOrder++, true, true, "FcpServer.allowedHosts", "FcpServer.allowedHostsLong", new FCPAllowedHostsCallback(core));
		fcpConfig.register("allowedHostsFullAccess", NetworkInterface.DEFAULT_BIND_TO, sortOrder++, true, true, "FcpServer.allowedHostsFullAccess", "FcpServer.allowedHostsFullAccessLong", new FCPAllowedHostsFullAccessCallback(core));
		
		AssumeDDADownloadIsAllowedCallback cb4;
		AssumeDDAUploadIsAllowedCallback cb5;
		NeverDropAMessageCallback cb6;
		MaxMessageQueueLengthCallback cb7;
		fcpConfig.register("assumeDownloadDDAIsAllowed", false, sortOrder++, true, false, "FcpServer.assumeDownloadDDAIsAllowed", "FcpServer.assumeDownloadDDAIsAllowedLong", cb4 = new AssumeDDADownloadIsAllowedCallback());
		fcpConfig.register("assumeUploadDDAIsAllowed", false, sortOrder++, true, false, "FcpServer.assumeUploadDDAIsAllowed", "FcpServer.assumeUploadDDAIsAllowedLong", cb5 = new AssumeDDAUploadIsAllowedCallback());
		fcpConfig.register("maxMessageQueueLength", 1024, sortOrder++, true, false, "FcpServer.maxMessageQueueLength", "FcpServer.maxMessageQueueLengthLong", cb7 = new MaxMessageQueueLengthCallback(), false);
		fcpConfig.register("neverDropAMessage", false, sortOrder++, true, false, "FcpServer.neverDropAMessage", "FcpServer.neverDropAMessageLong", cb6 = new NeverDropAMessageCallback());

		if(SSL.available()) {
			ssl = fcpConfig.getBoolean("ssl");
		}
		
		FCPServer fcp = new FCPServer(fcpConfig.getString("bindTo"), fcpConfig.getString("allowedHosts"), fcpConfig.getString("allowedHostsFullAccess"), fcpConfig.getInt("port"), node, core, fcpConfig.getBoolean("enabled"), fcpConfig.getBoolean("assumeDownloadDDAIsAllowed"), fcpConfig.getBoolean("assumeUploadDDAIsAllowed"), fcpConfig.getBoolean("neverDropAMessage"), fcpConfig.getInt("maxMessageQueueLength"), container);
		
		if(fcp != null) {
			cb4.server = fcp;
			cb5.server = fcp;
			cb6.server = fcp;
			cb7.server = fcp;
		}
		
		fcpConfig.finishedInitialization();
		return fcp;
	}

	public boolean neverDropAMessage() {
		return neverDropAMessage;
	}
	
	public int maxMessageQueueLength() {
		return maxMessageQueueLength;
	}
	
	private static String l10n(String key) {
		return NodeL10n.getBase().getString("FcpServer."+key);
	}
	
	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FcpServer."+key, pattern, value);
	}

	public FCPClient registerRebootClient(String name, NodeClientCore core, FCPConnectionHandler handler) {
		FCPClient oldClient;
		synchronized(this) {
			oldClient = rebootClientsByName.get(name);
			if(oldClient == null) {
				// Create new client
				FCPClient client = new FCPClient(name, handler, false, null, ClientRequest.PERSIST_REBOOT, null, whiteboard, null);
				rebootClientsByName.put(name, client);
				return client;
			} else {
				FCPConnectionHandler oldConn = oldClient.getConnection();
				// Have existing client
				if(oldConn == null) {
					// Easy
					oldClient.setConnection(handler);
					return oldClient;
				} else {
					// Kill old connection
					oldConn.setKilledDupe();
					oldConn.outputHandler.queue(new CloseConnectionDuplicateClientNameMessage());
					oldConn.close();
					oldClient.setConnection(handler);
					return oldClient;
				}
			}
		}
	}
	
	public FCPClient registerForeverClient(String name, NodeClientCore core, FCPConnectionHandler handler, ObjectContainer container) {
		return persistentRoot.registerForeverClient(name, core, handler, this, container);
	}

	public void unregisterClient(FCPClient client, ObjectContainer container) {
		if(client.persistenceType == ClientRequest.PERSIST_REBOOT) {
			assert(container == null);
		synchronized(this) {
			String name = client.name;
			rebootClientsByName.remove(name);
		}
		} else {
			persistentRoot.maybeUnregisterClient(client, container);
		}
	}

	public RequestStatus[] getGlobalRequests() throws DatabaseDisabledException {
		if(core.killedDatabase()) throw new DatabaseDisabledException();
		List<RequestStatus> v = new ArrayList<RequestStatus>();
		globalRebootClient.addPersistentRequestStatus(v);
		if(globalForeverClient != null)
			globalForeverClient.addPersistentRequestStatus(v);
		return v.toArray(new RequestStatus[v.size()]);
	}

	public boolean removeGlobalRequestBlocking(final String identifier) throws MessageInvalidException, DatabaseDisabledException {
		if(!globalRebootClient.removeByIdentifier(identifier, true, this, null, core.clientContext)) {
			final Object sync = new Object();
			final MutableBoolean done = new MutableBoolean();
			final MutableBoolean success = new MutableBoolean();
			done.value = false;
			core.clientContext.jobRunner.queue(new DBJob() {
				
				public String toString() {
					return "FCP removeGlobalRequestBlocking";
				}

				public boolean run(ObjectContainer container, ClientContext context) {
					boolean succeeded = false;
					try {
						succeeded = globalForeverClient.removeByIdentifier(identifier, true, FCPServer.this, container, core.clientContext);
					} catch (Throwable t) {
						Logger.error(this, "Caught removing identifier "+identifier+": "+t, t);
					} finally {
						synchronized(sync) {
							success.value = succeeded;
							done.value = true;
							sync.notifyAll();
						}
					}
					return true;
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
			synchronized(sync) {
				while(!done.value) {
					try {
						sync.wait();
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				return success.value;
			}
		} else return true;
	}
	
	public boolean removeAllGlobalRequestsBlocking() throws DatabaseDisabledException {
		globalRebootClient.removeAll(null, core.clientContext);
		
		final Object sync = new Object();
		final MutableBoolean done = new MutableBoolean();
		final MutableBoolean success = new MutableBoolean();
		done.value = false;
		core.clientContext.jobRunner.queue(new DBJob() {
			
			public String toString() {
				return "FCP removeAllGlobalRequestsBlocking";
			}

			public boolean run(ObjectContainer container, ClientContext context) {
				boolean succeeded = false;
				try {
					globalForeverClient.removeAll(container, core.clientContext);
					succeeded = true;
				} catch (Throwable t) {
					Logger.error(this, "Caught while processing panic: "+t, t);
					System.err.println("PANIC INCOMPLETE: CAUGHT "+t);
					t.printStackTrace();
					System.err.println("Your requests have not been deleted!");
				} finally {
					synchronized(sync) {
						success.value = succeeded;
						done.value = true;
						sync.notifyAll();
					}
				}
				return true;
			}
			
		}, NativeThread.HIGH_PRIORITY, false);
		synchronized(sync) {
			while(!done.value) {
				try {
					sync.wait();
				} catch (InterruptedException e) {
					// Ignore
				}
			}
			return success.value;
		}
	}

	public void makePersistentGlobalRequestBlocking(final FreenetURI fetchURI, final boolean filterData, final String expectedMimeType, final String persistenceTypeString, final String returnTypeString, final boolean realTimeFlag) throws NotAllowedException, IOException, DatabaseDisabledException {
		class OutputWrapper {
			NotAllowedException ne;
			IOException ioe;
			boolean done;
		}
		
		final OutputWrapper ow = new OutputWrapper();
		core.clientContext.jobRunner.queue(new DBJob() {
			
			public String toString() {
				return "FCP makePersistentGlobalRequestBlocking";
			}

			public boolean run(ObjectContainer container, ClientContext context) {
				NotAllowedException ne = null;
				IOException ioe = null;
				try {
					makePersistentGlobalRequest(fetchURI, filterData, expectedMimeType, persistenceTypeString, returnTypeString, realTimeFlag, container);
					return true;
				} catch (NotAllowedException e) {
					ne = e;
					return false;
				} catch (IOException e) {
					ioe = e;
					return false;
				} catch (Throwable t) {
					// Unexpected and severe, might even be OOM, just log it.
					Logger.error(this, "Failed to make persistent request: "+t, t);
					return false;
				} finally {
					synchronized(ow) {
						ow.ne = ne;
						ow.ioe = ioe;
						ow.done = true;
						ow.notifyAll();
					}
				}
			}
			
		}, NativeThread.HIGH_PRIORITY, false);
		
		synchronized(ow) {
			while(true) {
				if(!ow.done) {
					try {
						ow.wait();
					} catch (InterruptedException e) {
						// Ignore
					}
					continue;
				}
				if(ow.ioe != null) throw ow.ioe;
				if(ow.ne != null) throw ow.ne;
				return;
			}
		}
	}
	
	public boolean modifyGlobalRequestBlocking(final String identifier, final String newToken, final short newPriority) throws DatabaseDisabledException {
		ClientRequest req = this.globalRebootClient.getRequest(identifier, null);
		if(req != null) {
			req.modifyRequest(newToken, newPriority, this, null);
			return true;
		} else {
			class OutputWrapper {
				boolean success;
				boolean done;
			}
			final OutputWrapper ow = new OutputWrapper();
			core.clientContext.jobRunner.queue(new DBJob() {
				
				public String toString() {
					return "FCP modifyGlobalRequestBlocking";
				}

				public boolean run(ObjectContainer container, ClientContext context) {
					boolean success = false;
					try {
						ClientRequest req = globalForeverClient.getRequest(identifier, container);
						container.activate(req, 1);
						if(req != null)
							req.modifyRequest(newToken, newPriority, FCPServer.this, container);
						container.deactivate(req, 1);
						success = true;
					} finally {
						synchronized(ow) {
							ow.success = success;
							ow.done = true;
							ow.notifyAll();
						}
					}
					return true;
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
			
			synchronized(ow) {
				while(true) {
					if(!ow.done) {
						try {
							ow.wait();
						} catch (InterruptedException e) {
							// Ignore
						}
						continue;
					}
					return ow.success;
				}
			}
		}
	}
	
	/**
	 * Create a persistent globally-queued request for a file.
	 * @param fetchURI The file to fetch.
	 * @param persistence The persistence type.
	 * @param returnType The return type.
	 * @throws NotAllowedException 
	 * @throws IOException 
	 */
	public void makePersistentGlobalRequest(FreenetURI fetchURI, boolean filterData, String expectedMimeType, String persistenceTypeString, String returnTypeString, boolean realTimeFlag, ObjectContainer container) throws NotAllowedException, IOException {
		boolean persistence = persistenceTypeString.equalsIgnoreCase("reboot");
		short returnType = ClientGetMessage.parseReturnType(returnTypeString);
		File returnFilename = null, returnTempFilename = null;
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			returnFilename = makeReturnFilename(fetchURI, expectedMimeType);
			returnTempFilename = makeTempReturnFilename(returnFilename);
		}
//		public ClientGet(FCPClient globalClient, FreenetURI uri, boolean dsOnly, boolean ignoreDS, 
//				int maxSplitfileRetries, int maxNonSplitfileRetries, long maxOutputLength, 
//				short returnType, boolean persistRebootOnly, String identifier, int verbosity, short prioClass,
//				File returnFilename, File returnTempFilename) throws IdentifierCollisionException {
		
		try {
			innerMakePersistentGlobalRequest(fetchURI, filterData, persistence, returnType, "FProxy:"+fetchURI.getPreferredFilename(), returnFilename, returnTempFilename, realTimeFlag, container);
			return;
		} catch (IdentifierCollisionException ee) {
			try {
				innerMakePersistentGlobalRequest(fetchURI, filterData, persistence, returnType, "FProxy:"+fetchURI.getDocName(), returnFilename, returnTempFilename, realTimeFlag, container);
				return;
			} catch (IdentifierCollisionException e) {
				try {
					innerMakePersistentGlobalRequest(fetchURI, filterData, persistence, returnType, "FProxy:"+fetchURI.toString(false, false), returnFilename, returnTempFilename, realTimeFlag, container);
					return;
				} catch (IdentifierCollisionException e1) {
					// FIXME maybe use DateFormat
					try {
						innerMakePersistentGlobalRequest(fetchURI, filterData, persistence, returnType, "FProxy ("+System.currentTimeMillis()+ ')', returnFilename, returnTempFilename, realTimeFlag, container);
						return;
					} catch (IdentifierCollisionException e2) {
						while(true) {
							byte[] buf = new byte[8];
							try {
								core.random.nextBytes(buf);
								String id = "FProxy:"+Base64.encode(buf);
								innerMakePersistentGlobalRequest(fetchURI, filterData, persistence, returnType, id, returnFilename, returnTempFilename, realTimeFlag, container);
								return;
							} catch (IdentifierCollisionException e3) {}
						}
					}
				}
			}
			
		}
	}

	private File makeTempReturnFilename(File returnFilename) {
		return new File(returnFilename.toString() + ".freenet-tmp");
	}

	private File makeReturnFilename(FreenetURI uri, String expectedMimeType) {
		String ext;
		if((expectedMimeType != null) && (expectedMimeType.length() > 0) &&
				!expectedMimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE)) {
			ext = DefaultMIMETypes.getExtension(expectedMimeType);
		} else ext = null;
		String extAdd = (ext == null ? "" : '.' + ext);
		String preferred = uri.getPreferredFilename();
		String preferredWithExt = preferred;
		if(!(ext != null && preferredWithExt.endsWith(ext)))
			preferredWithExt += extAdd;
		File f = new File(core.getDownloadDir(), preferredWithExt);
		File f1 = new File(core.getDownloadDir(), preferredWithExt + ".freenet-tmp");
		int x = 0;
		StringBuilder sb = new StringBuilder();
		for(;f.exists() || f1.exists();sb.setLength(0)) {
			sb.append(preferred);
			sb.append('-');
			sb.append(x);
			sb.append(extAdd);
			f = new File(core.getDownloadDir(), sb.toString());
			f1 = new File(core.getDownloadDir(), sb.append(".freenet-tmp").toString());
			x++;
		}
		return f;
	}

	private void innerMakePersistentGlobalRequest(FreenetURI fetchURI, boolean filterData, boolean persistRebootOnly, short returnType, String id, File returnFilename,
			File returnTempFilename, boolean realTimeFlag, ObjectContainer container) throws IdentifierCollisionException, NotAllowedException, IOException {
		final ClientGet cg = 
			new ClientGet(persistRebootOnly ? globalRebootClient : globalForeverClient, fetchURI, defaultFetchContext.localRequestOnly, 
					defaultFetchContext.ignoreStore, filterData, QUEUE_MAX_RETRIES,
					QUEUE_MAX_RETRIES, QUEUE_MAX_DATA_SIZE, returnType, persistRebootOnly, id,
					Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, returnFilename, returnTempFilename, null, false, realTimeFlag, this, container);
		cg.register(container, false);
		cg.start(container, core.clientContext);
	}

	/**
	 * Returns the global FCP client.
	 * 
	 * @return The global FCP client
	 */
	public FCPClient getGlobalForeverClient() {
		return globalForeverClient;
	}
	
	public ClientRequest getGlobalRequest(String identifier, ObjectContainer container) {
		ClientRequest req = globalRebootClient.getRequest(identifier, null);
		if(req == null)
			req = globalForeverClient.getRequest(identifier, container);
		return req;
	}

	protected boolean isDownloadDDAAlwaysAllowed() {
		return assumeDownloadDDAIsAllowed;
	}

	protected boolean isUploadDDAAlwaysAllowed() {
		return assumeUploadDDAIsAllowed;
	}

	public void setCompletionCallback(RequestCompletionCallback cb) {
		if(globalForeverClient != null)
			globalForeverClient.addRequestCompletionCallback(cb);
		globalRebootClient.addRequestCompletionCallback(cb);
	}

	public void startBlocking(final ClientRequest req, ObjectContainer container, ClientContext context) throws IdentifierCollisionException, DatabaseDisabledException {
		if(req.persistenceType == ClientRequest.PERSIST_REBOOT) {
			req.start(null, core.clientContext);
		} else {
			class OutputWrapper {
				boolean done;
				IdentifierCollisionException collided;
			}
			if(container != null) {
				// Don't activate, it may not be stored yet.
					req.register(container, false);
					req.start(container, context);
				container.deactivate(req, 1);
			} else {
				final OutputWrapper ow = new OutputWrapper();
			core.clientContext.jobRunner.queue(new DBJob() {
				
				public String toString() {
					return "FCP startBlocking";
				}
				
				public boolean run(ObjectContainer container, ClientContext context) {
					// Don't activate, it may not be stored yet.
					try {
						req.register(container, false);
						req.start(container, context);
					} catch (IdentifierCollisionException e) {
						ow.collided = e;
					} finally {
						synchronized(ow) {
							ow.done = true;
							ow.notifyAll();
						}
					}
					container.deactivate(req, 1);
					return true;
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
			
			synchronized(ow) {
				while(true) {
					if(!ow.done) {
						try {
							ow.wait();
						} catch (InterruptedException e) {
							// Ignore
						}
					} else {
						if(ow.collided != null)
							throw ow.collided;
						return;
					}
				}
			}
		}
		}
	}
	
	public boolean restartBlocking(final String identifier, final boolean disableFilterData) throws DatabaseDisabledException {
		ClientRequest req = globalRebootClient.getRequest(identifier, null);
		if(req != null) {
			req.restart(null, core.clientContext, disableFilterData);
			return true;
		} else {
			class OutputWrapper {
				boolean done;
				boolean success;
			}
			final OutputWrapper ow = new OutputWrapper();
			core.clientContext.jobRunner.queue(new DBJob() {
				
				public String toString() {
					return "FCP restartBlocking";
				}

				public boolean run(ObjectContainer container, ClientContext context) {
					boolean success = false;
					try {
						ClientRequest req = globalForeverClient.getRequest(identifier, container);
						if(req != null) {
							req.restart(container, context, disableFilterData);
							success = true;
						}
					} catch (DatabaseDisabledException e) {
						success = false;
					} finally {
						synchronized(ow) {
							ow.success = success;
							ow.done = true;
							ow.notifyAll();
						}
					}
					return true;
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
			
			synchronized(ow) {
				while(true) {
					if(ow.done) return ow.success;
					try {
						ow.wait();
					} catch (InterruptedException e) {
						// Ignore
					}
				}
			}
		}
	}



	public FetchResult getCompletedRequestBlocking(final FreenetURI key) throws DatabaseDisabledException {
		ClientGet get = globalRebootClient.getCompletedRequest(key, null);
		if(get != null) {
			// FIXME race condition with free() - arrange refcounting for the data to prevent this
			return new FetchResult(new ClientMetadata(get.getMIMEType(null)), new NoFreeBucket(get.getBucket(null)));
		}
		
		FetchResult result = globalForeverClient.getRequestStatusCache().getShadowBucket(key, false);
		if(result != null) {
			return result;
		}
		
		class OutputWrapper {
			FetchResult result;
			boolean done;
		}
		
		final OutputWrapper ow = new OutputWrapper();
		
		core.clientContext.jobRunner.queue(new DBJob() {
			
			public String toString() {
				return "FCP getCompletedRequestBlocking";
			}

			public boolean run(ObjectContainer container, ClientContext context) {
				FetchResult result = null;
				try {
					result = lookup(key, false, context, container, false, null);
				} finally {
					synchronized(ow) {
						ow.result = result;
						ow.done = true;
						ow.notifyAll();
					}
				}
				return false;
			}
			
		}, NativeThread.HIGH_PRIORITY, false);
		
		synchronized(ow) {
			while(true) {
				if(ow.done) {
					return ow.result;
				} else {
					try {
						ow.wait();
					} catch (InterruptedException e) {
						// Ignore
					}
				}
			}
		}
	}

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing FCPServer in database", new Exception("error"));
		return false;
	}
	
	public Whiteboard getWhiteboard(){
		return whiteboard;
	}

	public CacheFetchResult lookupInstant(FreenetURI key, boolean noFilter, boolean mustCopy, Bucket preferred) {
		ClientGet get = globalRebootClient.getCompletedRequest(key, null);
		
		Bucket origData = null;
		String mime = null;
		boolean filtered = false;
		
		if(get != null && ((!noFilter) || (!(filtered = get.filterData(null))))) {
			origData = new NoFreeBucket(get.getBucket(null));
			mime = get.getMIMEType(null);
		}
		
		if(origData == null && globalForeverClient != null) {
			CacheFetchResult result = globalForeverClient.getRequestStatusCache().getShadowBucket(key, noFilter);
			if(result != null) {
				mime = result.getMimeType();
				origData = result.asBucket();
				filtered = result.alreadyFiltered;
			}
		}
		
		if(origData == null) return null;
		
		if(!mustCopy)
			return new CacheFetchResult(new ClientMetadata(mime), origData, filtered);
		
		Bucket newData = null;
		try {
			if(preferred != null) newData = preferred;
			else newData = core.tempBucketFactory.makeBucket(origData.size());
			BucketTools.copy(origData, newData);
			if(origData.size() != newData.size()) {
				Logger.normal(this, "Maybe it disappeared under us?");
				newData.free();
				newData = null;
				return null;
			}
			return new CacheFetchResult(new ClientMetadata(mime), newData, filtered);
		} catch (IOException e) {
			// Maybe it was freed?
			Logger.normal(this, "Unable to copy data: "+e, e);
			return null;
		}
		
	}

	public CacheFetchResult lookup(FreenetURI key, boolean noFilter, ClientContext context,
			ObjectContainer container, boolean mustCopy, Bucket preferred) {
		if(globalForeverClient == null) return null;
		ClientGet get = globalForeverClient.getCompletedRequest(key, container);
		container.activate(get, 1);
		if(get != null) {
			boolean filtered = get.filterData(container);
			Bucket origData = get.getBucket(container);
			container.activate(origData, 5);
			Bucket newData = null;
			if(!mustCopy)
				newData = origData.createShadow();
			if(newData == null) {
				try {
					if(preferred != null)
						newData = preferred;
					else
						newData = core.tempBucketFactory.makeBucket(origData.size());
					BucketTools.copy(origData, newData);
				} catch (IOException e) {
					Logger.error(this, "Unable to copy data: "+e, e);
					return null;
				}
			}
			container.deactivate(get, 1);
			return new CacheFetchResult(new ClientMetadata(get.getMIMEType(container)), newData, filtered);
		}
		return null;
	}
	
}
