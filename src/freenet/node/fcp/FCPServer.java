/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Iterator;
import java.util.Vector;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.tanukisoftware.wrapper.WrapperManager;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchContext;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertContext;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.SSL;
import freenet.io.AllowedHosts;
import freenet.io.NetworkInterface;
import freenet.io.SSLNetworkInterface;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.api.BooleanCallback;
import freenet.support.api.Bucket;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import java.util.LinkedList;

/**
 * FCP server process.
 */
public class FCPServer implements Runnable {

	final FCPPersistentRoot persistentRoot;
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
	final WeakHashMap clientsByName;
	final FCPClient globalRebootClient;
	final FCPClient globalForeverClient;
	private boolean enablePersistentDownloads;
	private File persistentDownloadsFile;
	private File persistentDownloadsTempFile;
	/** Lock for persistence operations.
	 * MUST ALWAYS BE THE OUTERMOST LOCK.
	 */
	private final Object persistenceSync = new Object();
	private boolean haveLoadedPersistentRequests;
	final FetchContext defaultFetchContext;
	public InsertContext defaultInsertContext;
	public static final int QUEUE_MAX_RETRIES = -1;
	public static final long QUEUE_MAX_DATA_SIZE = Long.MAX_VALUE;
	private boolean assumeDownloadDDAIsAllowed;
	private boolean assumeUploadDDAIsAllowed;
	private boolean hasFinishedStart;
	
	public FCPServer(String ipToBindTo, String allowedHosts, String allowedHostsFullAccess, int port, Node node, NodeClientCore core, boolean persistentDownloadsEnabled, String persistentDownloadsDir, boolean isEnabled, boolean assumeDDADownloadAllowed, boolean assumeDDAUploadAllowed, ObjectContainer container) throws IOException, InvalidConfigValueException {
		this.bindTo = ipToBindTo;
		this.allowedHosts=allowedHosts;
		this.allowedHostsFullAccess = new AllowedHosts(allowedHostsFullAccess);
		this.port = port;
		this.enabled = isEnabled;
		this.enablePersistentDownloads = persistentDownloadsEnabled;
		setPersistentDownloadsFile(new File(persistentDownloadsDir));
		this.node = node;
		this.core = core;
		this.assumeDownloadDDAIsAllowed = assumeDDADownloadAllowed;
		this.assumeUploadDDAIsAllowed = assumeDDAUploadAllowed;
		clientsByName = new WeakHashMap();
		
		// This one is only used to get the default settings. Individual FCP conns
		// will make their own.
		HighLevelSimpleClient client = core.makeClient((short)0);
		defaultFetchContext = client.getFetchContext();
		defaultInsertContext = client.getInsertContext(false);
		
		globalRebootClient = new FCPClient("Global Queue", null, true, null, ClientRequest.PERSIST_REBOOT, null);
		
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		if(enabled && enablePersistentDownloads) {
			loadPersistentRequests();
		} else {
			Logger.error(this, "Not loading persistent requests: enabled="+enabled+" enable persistent downloads="+enablePersistentDownloads);
		}
		persistentRoot = FCPPersistentRoot.create(node.nodeDBHandle, container);
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
				realRun();
			} catch (IOException e) {
				if(logMINOR) Logger.minor(this, "Caught "+e, e);
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
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

	static class FCPPortNumberCallback implements IntCallback {

		private final NodeClientCore node;
		
		FCPPortNumberCallback(NodeClientCore node) {
			this.node = node;
		}
		
		public int get() {
			return node.getFCPServer().port;
		}

		public void set(int val) throws InvalidConfigValueException {
			if(val != get()) {
				throw new InvalidConfigValueException("Cannot change FCP port number on the fly");
			}
		}
	}
	
	static class FCPEnabledCallback implements BooleanCallback{

		final NodeClientCore node;
		
		FCPEnabledCallback(NodeClientCore node) {
			this.node = node;
		}
		
		public boolean get() {
			return node.getFCPServer().enabled;
		}
//TODO: Allow it
		public void set(boolean val) throws InvalidConfigValueException {
			if(val != get()) {
				throw new InvalidConfigValueException(l10n("cannotStartOrStopOnTheFly"));
			}
		}
	}

	static class FCPSSLCallback implements BooleanCallback{

		public boolean get() {
			return ssl;
		}

		public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
			if(!SSL.available()) {
				throw new InvalidConfigValueException("Enable SSL support before use ssl with FCP");
			}
			ssl = val;
			throw new InvalidConfigValueException("Cannot change SSL on the fly, please restart freenet");
		}
	}

	// FIXME: Consider moving everything except enabled into constructor
	// Actually we could move enabled in too with an exception???
	
	static class FCPBindtoCallback implements StringCallback{

		final NodeClientCore node;
		
		FCPBindtoCallback(NodeClientCore node) {
			this.node = node;
		}
		
		public String get() {
			return node.getFCPServer().bindTo;
		}

		public void set(String val) throws InvalidConfigValueException {
			if(!val.equals(get())) {
				try {
					node.getFCPServer().networkInterface.setBindTo(val, true);
					node.getFCPServer().bindTo = val;
				} catch (IOException e) {
					// This is an advanced option for reasons of reducing clutter,
					// but it is expected to be used by regular users, not devs.
					// So we translate the error messages.
					throw new InvalidConfigValueException(l10n("couldNotChangeBindTo", "error", e.getLocalizedMessage()));
				}
			}
		}
	}
	
	static class FCPAllowedHostsCallback implements StringCallback {

		private final NodeClientCore node;
		
		public FCPAllowedHostsCallback(NodeClientCore node) {
			this.node = node;
		}
		
		public String get() {
			FCPServer server = node.getFCPServer();
			if(server == null) return NetworkInterface.DEFAULT_BIND_TO;
			NetworkInterface netIface = server.networkInterface;
			return (netIface == null ? NetworkInterface.DEFAULT_BIND_TO : netIface.getAllowedHosts());
		}

		public void set(String val) {
			if (!val.equals(get())) {
				node.getFCPServer().networkInterface.setAllowedHosts(val);
			}
		}
		
	}

	static class FCPAllowedHostsFullAccessCallback implements StringCallback {

		private final NodeClientCore node;
		
		public FCPAllowedHostsFullAccessCallback(NodeClientCore node) {
			this.node = node;
		}
		
		public String get() {
			return node.getFCPServer().allowedHostsFullAccess.getAllowedHosts();
		}

		public void set(String val) {
			if (!val.equals(get())) {
				node.getFCPServer().allowedHostsFullAccess.setAllowedHosts(val);
			}
		}
		
	}

	static class PersistentDownloadsFileCallback implements StringCallback {
		
		FCPServer server;
		
		public String get() {
			return server.persistentDownloadsFile.toString();
		}
		
		public void set(String val) throws InvalidConfigValueException {
			File f = new File(val);
			if(f.equals(server.persistentDownloadsFile)) return;
			server.setPersistentDownloadsFile(f);
		}
	}

	static class AssumeDDADownloadIsAllowedCallback implements BooleanCallback{
		FCPServer server;

		public boolean get() {
			return server.assumeDownloadDDAIsAllowed;
		}
		
		public void set(boolean val) throws InvalidConfigValueException {
			if(val == get()) return;
			server.assumeDownloadDDAIsAllowed = val;
		}
	}
	
	static class AssumeDDAUploadIsAllowedCallback implements BooleanCallback{
		FCPServer server;

		public boolean get() {
			return server.assumeUploadDDAIsAllowed;
		}
		
		public void set(boolean val) throws InvalidConfigValueException {
			if(val == get()) return;
			server.assumeUploadDDAIsAllowed = val;
		}
	}

	
	public static FCPServer maybeCreate(Node node, NodeClientCore core, Config config, ObjectContainer container) throws IOException, InvalidConfigValueException {
		SubConfig fcpConfig = new SubConfig("fcp", config);
		short sortOrder = 0;
		fcpConfig.register("enabled", true, sortOrder++, true, false, "FcpServer.isEnabled", "FcpServer.isEnabledLong", new FCPEnabledCallback(core));
		fcpConfig.register("ssl", false, sortOrder++, true, true, "FcpServer.ssl", "FcpServer.sslLong", new FCPSSLCallback());
		fcpConfig.register("port", FCPServer.DEFAULT_FCP_PORT /* anagram of 1984, and 1000 up from old number */, 2, true, true, "FcpServer.portNumber", "FcpServer.portNumberLong", new FCPPortNumberCallback(core));
		fcpConfig.register("bindTo", NetworkInterface.DEFAULT_BIND_TO, sortOrder++, true, true, "FcpServer.bindTo", "FcpServer.bindToLong", new FCPBindtoCallback(core));
		fcpConfig.register("allowedHosts", NetworkInterface.DEFAULT_BIND_TO, sortOrder++, true, true, "FcpServer.allowedHosts", "FcpServer.allowedHostsLong", new FCPAllowedHostsCallback(core));
		fcpConfig.register("allowedHostsFullAccess", NetworkInterface.DEFAULT_BIND_TO, sortOrder++, true, true, "FcpServer.allowedHostsFullAccess", "FcpServer.allowedHostsFullAccessLong", new FCPAllowedHostsFullAccessCallback(core));
		PersistentDownloadsFileCallback cb2;
		fcpConfig.register("persistentDownloadsEnabled", true, sortOrder++, true, true, "FcpServer.enablePersistentDownload", "FcpServer.enablePersistentDownloadLong", (BooleanCallback) null);
		fcpConfig.register("persistentDownloadsFile", "downloads.dat", sortOrder++, true, false, "FcpServer.filenameToStorePData", "FcpServer.filenameToStorePDataLong", cb2 = new PersistentDownloadsFileCallback());
		fcpConfig.register("persistentDownloadsInterval", (5*60*1000), sortOrder++, true, false, "FcpServer.intervalBetweenWrites", "FcpServer.intervalBetweenWritesLong", (IntCallback) null);
		String persistentDownloadsDir = fcpConfig.getString("persistentDownloadsFile");
		boolean persistentDownloadsEnabled = fcpConfig.getBoolean("persistentDownloadsEnabled");		
		
		AssumeDDADownloadIsAllowedCallback cb4;
		AssumeDDAUploadIsAllowedCallback cb5;
		fcpConfig.register("assumeDownloadDDAIsAllowed", false, sortOrder++, true, false, "FcpServer.assumeDownloadDDAIsAllowed", "FcpServer.assumeDownloadDDAIsAllowedLong", cb4 = new AssumeDDADownloadIsAllowedCallback());
		fcpConfig.register("assumeUploadDDAIsAllowed", false, sortOrder++, true, false, "FcpServer.assumeUploadDDAIsAllowed", "FcpServer.assumeUploadDDAIsAllowedLong", cb5 = new AssumeDDAUploadIsAllowedCallback());

		if(SSL.available()) {
			ssl = fcpConfig.getBoolean("ssl");
		}
		
		FCPServer fcp = new FCPServer(fcpConfig.getString("bindTo"), fcpConfig.getString("allowedHosts"), fcpConfig.getString("allowedHostsFullAccess"), fcpConfig.getInt("port"), node, core, persistentDownloadsEnabled, persistentDownloadsDir, fcpConfig.getBoolean("enabled"), fcpConfig.getBoolean("assumeDownloadDDAIsAllowed"), fcpConfig.getBoolean("assumeUploadDDAIsAllowed"), container);
		
		if(fcp != null) {
			cb2.server = fcp;
			cb4.server = fcp;
			cb5.server = fcp;
		}
		
		fcpConfig.finishedInitialization();
		return fcp;
	}

	public void setPersistentDownloadsFile(File f) throws InvalidConfigValueException {
		synchronized(persistenceSync) {
			checkFile(f);
			File temp = new File(f.getPath()+".tmp");
			checkFile(temp);
			// Else is ok
			persistentDownloadsFile = f;
			persistentDownloadsTempFile = temp;
		}
	}

	private void checkFile(File f) throws InvalidConfigValueException {
		if(f.isDirectory()) 
			throw new InvalidConfigValueException(l10n("downloadsFileIsDirectory"));
		if(f.isFile() && !(f.canRead() && f.canWrite()))
			throw new InvalidConfigValueException(l10n("downloadsFileUnreadable"));
		File parent = f.getParentFile();
		if((parent != null) && !parent.exists())
			throw new InvalidConfigValueException(l10n("downloadsFileParentDoesNotExist"));
		if(!f.exists()) {
			try {
				if(!f.createNewFile()) {
					if(f.exists()) {
						if(!(f.canRead() && f.canWrite())) {
							throw new InvalidConfigValueException(l10n("downloadsFileExistsCannotReadOrWrite"));
						} // else ok
					} else {
						throw new InvalidConfigValueException(l10n("downloadsFileDoesNotExistCannotCreate"));
					}
				} else {
					if(!(f.canRead() && f.canWrite())) {
						throw new InvalidConfigValueException(l10n("downloadsFileCanCreateCannotReadOrWrite"));
					}
				}
			} catch (IOException e) {
				throw new InvalidConfigValueException(l10n("downloadsFileDoesNotExistCannotCreate")+ " : "+e.getLocalizedMessage());
			} finally {
				// Must be deleted, otherwise we will read from it and ignore the temp file => lose the queue.
				f.delete();
			}
		}
	}

	private static String l10n(String key) {
		return L10n.getString("FcpServer."+key);
	}
	
	private static String l10n(String key, String pattern, String value) {
		return L10n.getString("FcpServer."+key, pattern, value);
	}

	public FCPClient registerRebootClient(String name, NodeClientCore core, FCPConnectionHandler handler) {
		FCPClient oldClient;
		synchronized(this) {
			oldClient = (FCPClient) clientsByName.get(name);
			if(oldClient == null) {
				// Create new client
				FCPClient client = new FCPClient(name, handler, false, null, ClientRequest.PERSIST_REBOOT, null);
				clientsByName.put(name, client);
				return client;
			} else {
				FCPConnectionHandler oldConn = oldClient.getConnection();
				// Have existing client
				if(oldConn == null) {
					// Easy
					oldClient.setConnection(handler);
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
		if(handler != null)
			oldClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler, null);
		return oldClient;
	}
	
	public FCPClient registerForeverClient(String name, NodeClientCore core, FCPConnectionHandler handler, ObjectContainer container) {
		return persistentRoot.registerForeverClient(name, core, handler, this, container);
	}

	public void unregisterClient(FCPClient client, ObjectContainer container) {
		if(client.persistenceType == ClientRequest.PERSIST_REBOOT) {
			assert(container == null);
		synchronized(this) {
			String name = client.name;
			clientsByName.remove(name);
		}
		} else {
			persistentRoot.maybeUnregisterClient(client, container);
		}
	}

	private void loadPersistentRequests() {
		Logger.normal(this, "Loading persistent requests...");
		FileInputStream fis = null;
		BufferedInputStream bis = null;
		GZIPInputStream gis = null;
		try {
			File file = new File(persistentDownloadsFile+".gz");
			fis = new FileInputStream(file);
			gis = new GZIPInputStream(fis);
			bis = new BufferedInputStream(gis);
			Logger.normal(this, "Loading persistent requests from "+file);
			if (file.length() > 0) {
				loadPersistentRequests(bis);
				haveLoadedPersistentRequests = true;
			} else
				throw new IOException("File empty"); // If it's empty, try the temp file.
		} catch (IOException e) {
			Logger.error(this, "IOE : " + e.getMessage(), e);
			File file = new File(persistentDownloadsTempFile+".gz");
			Logger.normal(this, "Let's try to load "+file+" then.");
			Closer.close(bis);
			Closer.close(gis);
			Closer.close(fis);
			try {
				fis = new FileInputStream(file);
				bis = new BufferedInputStream(fis);
				loadPersistentRequests(bis);
				haveLoadedPersistentRequests = true;
			} catch (IOException e1) {
				Logger.normal(this, "It's corrupted too : Not reading any persistent requests from disk: "+e1);
				return;
			}
		} finally {
			Closer.close(bis);
			Closer.close(gis);
			Closer.close(fis);
		}
	}
	
	private void loadPersistentRequests(InputStream is) throws IOException {
		synchronized(persistenceSync) {
			InputStreamReader ris = new InputStreamReader(is, "UTF-8");
			BufferedReader br = new BufferedReader(ris);
			try {
				String r = br.readLine();
				int count;
				try {
					count = Integer.parseInt(r);
				} catch(NumberFormatException e) {
					Logger.error(this, "Corrupt persistent downloads file: cannot parse "+r+" as integer");
					throw new IOException(e.toString());
				}
				for(int i = 0; i < count; i++) {
					WrapperManager.signalStarting(20 * 60 * 1000);  // 20 minutes per request; must be >ds lock timeout (10 minutes)
					System.out.println("Loading persistent request " + (i + 1) + " of " + count + "..."); // humans count from 1..
					ClientRequest.readAndRegister(br, this);
				}
				Logger.normal(this, "Loaded "+count+" persistent requests");
			}
			finally {
				Closer.close(br);
				Closer.close(ris);
			}
		}
	}

	public ClientRequest[] getGlobalRequests() {
		Vector v = new Vector();
		globalClient.addPersistentRequests(v, false);
		return (ClientRequest[]) v.toArray(new ClientRequest[v.size()]);
	}

	public void removeGlobalRequest(String identifier) throws MessageInvalidException {
		globalClient.removeByIdentifier(identifier, true);
	}

	/**
	 * Create a persistent globally-queued request for a file.
	 * @param fetchURI The file to fetch.
	 * @param persistence The persistence type.
	 * @param returnType The return type.
	 * @throws NotAllowedException 
	 */
	public void makePersistentGlobalRequest(FreenetURI fetchURI, String expectedMimeType, String persistenceTypeString, String returnTypeString) throws NotAllowedException {
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
			innerMakePersistentGlobalRequest(fetchURI, persistence, returnType, "FProxy:"+fetchURI.getPreferredFilename(), returnFilename, returnTempFilename);
			return;
		} catch (IdentifierCollisionException ee) {
			try {
				innerMakePersistentGlobalRequest(fetchURI, persistence, returnType, "FProxy:"+fetchURI.getDocName(), returnFilename, returnTempFilename);
				return;
			} catch (IdentifierCollisionException e) {
				try {
					innerMakePersistentGlobalRequest(fetchURI, persistence, returnType, "FProxy:"+fetchURI.toString(false, false), returnFilename, returnTempFilename);
					return;
				} catch (IdentifierCollisionException e1) {
					// FIXME maybe use DateFormat
					try {
						innerMakePersistentGlobalRequest(fetchURI, persistence, returnType, "FProxy ("+System.currentTimeMillis()+ ')', returnFilename, returnTempFilename);
						return;
					} catch (IdentifierCollisionException e2) {
						while(true) {
							byte[] buf = new byte[8];
							try {
								core.random.nextBytes(buf);
								String id = "FProxy:"+Base64.encode(buf);
								innerMakePersistentGlobalRequest(fetchURI, persistence, returnType, id, returnFilename, returnTempFilename);
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
		StringBuffer sb = new StringBuffer();
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

	private void innerMakePersistentGlobalRequest(FreenetURI fetchURI, boolean persistRebootOnly, short returnType, String id, File returnFilename, 
			File returnTempFilename) throws IdentifierCollisionException, NotAllowedException {
		ClientGet cg = 
			new ClientGet(persistRebootOnly ? globalRebootClient : globalForeverClient, fetchURI, defaultFetchContext.localRequestOnly, 
					defaultFetchContext.ignoreStore, QUEUE_MAX_RETRIES, QUEUE_MAX_RETRIES,
					QUEUE_MAX_DATA_SIZE, returnType, persistRebootOnly, id, Integer.MAX_VALUE,
					RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, returnFilename, returnTempFilename, this);
		cg.start();
	}

	/**
	 * Start requests which were not started immediately because it might have taken
	 * some time to start them.
	 */
	public void finishStart() {
		this.globalForeverClient.finishStart();
		
		FCPClient[] clients;
		synchronized(this) {
			clients = (FCPClient[]) clientsByName.values().toArray(new FCPClient[clientsByName.size()]);
		}
		
		for(int i=0;i<clients.length;i++) {
			clients[i].finishStart();
		}
		
		hasFinishedStart = true;
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

	public boolean hasFinishedStart() {
		return hasFinishedStart;
	}
	
	public void setCompletionCallback(RequestCompletionCallback cb) {
		if(globalForeverClient.setRequestCompletionCallback(cb) != null)
			Logger.error(this, "Replacing request completion callback "+cb, new Exception("error"));
		if(globalRebootClient.setRequestCompletionCallback(cb) != null)
			Logger.error(this, "Replacing request completion callback "+cb, new Exception("error"));
	}
	
}
