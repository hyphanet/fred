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
import java.net.BindException;
import java.net.Socket;
import java.util.Iterator;
import java.util.Vector;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetcherContext;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InserterContext;
import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.LongCallback;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.io.NetworkInterface;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.io.Bucket;

/**
 * FCP server process.
 */
public class FCPServer implements Runnable {

	private static boolean logMINOR;
	public final static int DEFAULT_FCP_PORT = 9481;
	NetworkInterface networkInterface;
	final NodeClientCore core;
	final Node node;
	final int port;
	public final boolean enabled;
	String bindTo;
	String allowedHosts;
	final WeakHashMap clientsByName;
	final FCPClient globalClient;
	private boolean enablePersistentDownloads;
	private File persistentDownloadsFile;
	private File persistentDownloadsTempFile;
	/** Lock for persistence operations.
	 * MUST ALWAYS BE THE OUTERMOST LOCK.
	 */
	private final Object persistenceSync = new Object();
	private FCPServerPersister persister;
	private boolean haveLoadedPersistentRequests;
	private long persistenceInterval;
	final FetcherContext defaultFetchContext;
	public InserterContext defaultInsertContext;
	public static final int QUEUE_MAX_RETRIES = -1;
	public static final long QUEUE_MAX_DATA_SIZE = Long.MAX_VALUE;
	private boolean canStartPersister = false;
	
	private void startPersister() {
		Thread t = new Thread(persister = new FCPServerPersister(), "FCP request persistence handler");
		t.setDaemon(true);
		t.start();
	}

	private void killPersister() {
		persister.kill();
		persister = null;
	}

	public FCPServer(String ipToBindTo, String allowedHosts, int port, Node node, NodeClientCore core, boolean persistentDownloadsEnabled, String persistentDownloadsDir, long persistenceInterval, boolean isEnabled) throws IOException, InvalidConfigValueException {
		this.bindTo = ipToBindTo;
		this.allowedHosts = allowedHosts;
		this.persistenceInterval = persistenceInterval;
		this.port = port;
		this.enabled = isEnabled;
		this.enablePersistentDownloads = persistentDownloadsEnabled;
		setPersistentDownloadsFile(new File(persistentDownloadsDir));
		this.node = node;
		this.core = core;
		clientsByName = new WeakHashMap();
		
		
		// This one is only used to get the default settings. Individual FCP conns
		// will make their own.
		HighLevelSimpleClient client = core.makeClient((short)0);
		defaultFetchContext = client.getFetcherContext();
		defaultInsertContext = client.getInserterContext(false);
		
		
		globalClient = new FCPClient("Global Queue", this, null, true);
		
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public void maybeStart() throws IOException, InvalidConfigValueException {
		if (this.enabled) {
			
			if(enablePersistentDownloads) {
				loadPersistentRequests();
			}
			
			Logger.normal(this, "Starting FCP server on "+bindTo+":"+port+".");
			System.out.println("Starting FCP server on "+bindTo+":"+port+".");
			NetworkInterface tempNetworkInterface = null;
			try {
				tempNetworkInterface = new NetworkInterface(port, bindTo, allowedHosts);
			} catch (BindException be) {
				Logger.error(this, "Couldn't bind to FCP Port "+bindTo+":"+port+". FCP Server not started.");
				System.out.println("Couldn't bind to FCP Port "+bindTo+":"+port+". FCP Server not started.");
			}
			
			this.networkInterface = tempNetworkInterface;
			
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
		while(true) {
			try {
				realRun();
			} catch (IOException e) {
				if(logMINOR) Logger.minor(this, "Caught "+e, e);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
	}

	private void realRun() throws IOException {
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
				throw new InvalidConfigValueException("Cannot change the status of the FCP server on the fly");
			}
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
					node.getFCPServer().networkInterface.setBindTo(val);
					node.getFCPServer().bindTo = val;
				} catch (IOException e) {
					throw new InvalidConfigValueException("could not change bind to!");
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
			return node.getFCPServer().allowedHosts;
		}

		public void set(String val) {
			if (!val.equals(get())) {
				node.getFCPServer().networkInterface.setAllowedHosts(val);
				node.getFCPServer().allowedHosts = val;
			}
		}
		
	}

	static class PersistentDownloadsEnabledCallback implements BooleanCallback {
		
		FCPServer server;
		
		public boolean get() {
			return server.persistentDownloadsEnabled();
		}
		
		public void set(boolean set) {
			if(server.persistentDownloadsEnabled() != set)
				server.setPersistentDownloadsEnabled(set);
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

	static class PersistentDownloadsIntervalCallback implements LongCallback {
		
		FCPServer server;
		
		public long get() {
			return server.persistenceInterval;
		}
		
		public void set(long value) {
			server.persistenceInterval = value;
			FCPServerPersister p = server.persister;
			if(p != null) {
				synchronized(p) {
					p.notify();
				}
			}
		}
	}
	
	public static FCPServer maybeCreate(Node node, NodeClientCore core, Config config) throws IOException, InvalidConfigValueException {
		SubConfig fcpConfig = new SubConfig("fcp", config);
		fcpConfig.register("enabled", true, 2, true, "Is FCP server enabled ?", "Is FCP server enabled ?", new FCPEnabledCallback(core));
		fcpConfig.register("port", FCPServer.DEFAULT_FCP_PORT /* anagram of 1984, and 1000 up from old number */,
				2, true, "FCP port number", "FCP port number", new FCPPortNumberCallback(core));
		fcpConfig.register("bindTo", "127.0.0.1", 2, true, "IP address to bind to", "IP address to bind the FCP server to", new FCPBindtoCallback(core));
		fcpConfig.register("allowedHosts", "127.0.0.1", 2, true, "Allowed hosts", "Hostnames or IP addresses that are allowed to connect to the FCP server. May be a comma-separated list of hostnames, single IPs and even CIDR masked IPs like 192.168.0.0/24", new FCPAllowedHostsCallback(core));
		PersistentDownloadsEnabledCallback cb1;
		PersistentDownloadsFileCallback cb2;
		PersistentDownloadsIntervalCallback cb3;
		fcpConfig.register("persistentDownloadsEnabled", true, 3, true, "Enable persistent downloads?", "Whether to enable Persistence=forever for FCP requests. Meaning whether to support requests which persist over node restarts; they must be written to disk and this may constitute a security risk for some people.",
				cb1 = new PersistentDownloadsEnabledCallback());
		boolean persistentDownloadsEnabled = fcpConfig.getBoolean("persistentDownloadsEnabled");
		fcpConfig.register("persistentDownloadsFile", "downloads.dat", 4, true, "Filename to store persistent downloads in", "Filename to store details of persistent downloads to",
				cb2 = new PersistentDownloadsFileCallback());
		String persistentDownloadsDir = 
			fcpConfig.getString("persistentDownloadsFile");
		
		fcpConfig.register("persistentDownloadsInterval", (5*60*1000), 5, true, "Interval between writing persistent downloads to disk", "Interval between writing persistent downloads to disk",
				cb3 = new PersistentDownloadsIntervalCallback());
		
		long persistentDownloadsInterval = fcpConfig.getLong("persistentDownloadsInterval");
		
		FCPServer fcp;
		
		fcp = new FCPServer(fcpConfig.getString("bindTo"), fcpConfig.getString("allowedHosts"), fcpConfig.getInt("port"), node, core, persistentDownloadsEnabled, persistentDownloadsDir, persistentDownloadsInterval, fcpConfig.getBoolean("enabled"));
		core.setFCPServer(fcp);	
		
		if(fcp != null) {
			cb1.server = fcp;
			cb2.server = fcp;
			cb3.server = fcp;
		}
		
	
		fcpConfig.finishedInitialization();
		if(fcp != null)
			fcp.maybeStart();

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
			throw new InvalidConfigValueException("Invalid filename for downloads list: is a directory");
		if(f.isFile() && !(f.canRead() && f.canWrite()))
			throw new InvalidConfigValueException("File exists but cannot be read");
		File parent = f.getParentFile();
		if((parent != null) && !parent.exists())
			throw new InvalidConfigValueException("Parent directory does not exist");
		if(!f.exists()) {
			try {
				if(!((f.createNewFile() || f.exists()) && (f.canRead() && f.canWrite())))
					throw new InvalidConfigValueException("File does not exist, cannot create it and/or cannot read/write it");
			} catch (IOException e) {
				throw new InvalidConfigValueException("File does not exist and cannot be created");
			}
		}
	}

	public void setPersistentDownloadsEnabled(boolean set) {
		synchronized(persistenceSync) {
			if(enablePersistentDownloads == set) return;
			if(set) {
				if(!haveLoadedPersistentRequests)
					loadPersistentRequests();
				if(canStartPersister)
					startPersister();
			} else {
				killPersister();
			}
			enablePersistentDownloads = set;
		}
	}

	public boolean persistentDownloadsEnabled() {
		synchronized(persistenceSync) {
			return enablePersistentDownloads;
		}
	}

	public FCPClient registerClient(String name, NodeClientCore core, FCPConnectionHandler handler) {
		FCPClient oldClient;
		synchronized(this) {
			oldClient = (FCPClient) clientsByName.get(name);
			if(oldClient == null) {
				// Create new client
				FCPClient client = new FCPClient(name, this, handler, false);
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
					oldConn.outputHandler.queue(new CloseConnectionDuplicateClientNameMessage());
					oldConn.close();
					oldClient.setConnection(handler);
					return oldClient;
				}
			}
		}
		if(handler != null)
			oldClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler);
		return oldClient;
	}

	public void unregisterClient(FCPClient client) {
		synchronized(this) {
			String name = client.name;
			clientsByName.remove(name);
		}
	}

	class FCPServerPersister implements Runnable {

		private boolean killed;
		private boolean storeNow;
		
		public void force() {
			synchronized(this) {
				storeNow = true;
				notifyAll();
			}
		}
		
		void kill() {
			synchronized(this) {
				killed = true;
				notifyAll();
			}
		}
		
		public void run() {
			while(true) {
				long startTime = System.currentTimeMillis();
				try {
					storePersistentRequests();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				}
				long delta = System.currentTimeMillis() - startTime;
				synchronized(this) {
					long delay = Math.max(persistenceInterval, delta * 20);
					if(killed) return;
					startTime = System.currentTimeMillis();
					long now;
					while(((now = System.currentTimeMillis()) < startTime + delay) && !storeNow) {
						try {
							long wait = Math.max((startTime + delay) - now, Integer.MAX_VALUE);
							if(wait > 0)
								wait(Math.min(wait, 5000));
						} catch (InterruptedException e) {
							// Ignore
						}
						if(killed) return;
					}
					storeNow = false;
				}
			}
		}
		
	}

	public void forceStorePersistentRequests() {
		if(logMINOR) Logger.minor(this, "Forcing store persistent requests");
		if(!enablePersistentDownloads) return;
		if(persister != null) {
			persister.force();
		} else {
			if(canStartPersister)
				Logger.error(this, "Persister not running, cannot store persistent requests");
		}
	}
	
	/** Store all persistent requests to disk */
	private void storePersistentRequests() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Storing persistent requests");
		ClientRequest[] persistentRequests = getPersistentRequests();
		if(logMINOR) Logger.minor(this, "Persistent requests count: "+persistentRequests.length);
		Bucket[] toFree = null;
		try {
			synchronized(persistenceSync) {
				toFree = core.persistentTempBucketFactory.grabBucketsToFree();
				try {
					File compressedTemp = new File(persistentDownloadsTempFile+".gz");
					File compressedFinal = new File(persistentDownloadsFile.toString()+".gz");
					FileOutputStream fos = new FileOutputStream(compressedTemp);
					BufferedOutputStream bos = new BufferedOutputStream(fos);
					GZIPOutputStream gos = new GZIPOutputStream(bos);
					OutputStreamWriter osw = new OutputStreamWriter(gos);
					BufferedWriter w = new BufferedWriter(osw);
					w.write(Integer.toString(persistentRequests.length)+"\n");
					for(int i=0;i<persistentRequests.length;i++)
						persistentRequests[i].write(w);
					w.close();
					if(!compressedTemp.renameTo(compressedFinal)) {
						if(logMINOR) Logger.minor(this, "Rename failed");
						compressedFinal.delete();
						if(!compressedTemp.renameTo(compressedFinal)) {
							Logger.error(this, "Could not rename persisted requests temp file "+persistentDownloadsTempFile+".gz to "+persistentDownloadsFile);
						}
					}
				} catch (IOException e) {
					Logger.error(this, "Cannot write persistent requests to disk: "+e);
				}
			}
			if(logMINOR) Logger.minor(this, "Stored persistent requests");
		} finally {
			if(toFree != null) {
				for(int i=0;i<toFree.length;i++)
					toFree[i].free();
			}
		}
	}

	private void loadPersistentRequests() {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(persistentDownloadsFile+".gz");
			GZIPInputStream gis = new GZIPInputStream(fis);
			BufferedInputStream bis = new BufferedInputStream(gis);
			loadPersistentRequests(bis);
			persistentDownloadsFile.delete();
		} catch (IOException e) {
			if(fis != null) {
				try {
					fis.close();
				} catch (IOException e1) {
					// Ignore
				}
				fis = null;
			}
			try {
				fis = new FileInputStream(persistentDownloadsFile);
				BufferedInputStream bis = new BufferedInputStream(fis);
				loadPersistentRequests(bis);
			} catch (IOException e1) {
				Logger.normal(this, "Not reading any persistent requests from disk: "+e1);
				return;
			}
		} finally {
			if(fis != null) {
				try {
					fis.close();
				} catch (IOException e) {
					// Ignore
				}
			}
		}
	}
	
	private void loadPersistentRequests(InputStream is) throws IOException {
		synchronized(persistenceSync) {
			InputStreamReader ris = new InputStreamReader(is);
			BufferedReader br = new BufferedReader(ris);
			String r = br.readLine();
			int count;
			try {
				count = Integer.parseInt(r);
			} catch (NumberFormatException e) {
				Logger.error(this, "Corrupt persistent downloads file: "+persistentDownloadsFile);
				throw new IOException(e.toString());
			}
			for(int i=0;i<count;i++) {
				ClientRequest.readAndRegister(br, this);
			}
			br.close();
		}
	}

	private ClientRequest[] getPersistentRequests() {
		Vector v = new Vector();
		synchronized(this) {
			Iterator i = clientsByName.values().iterator();
			while(i.hasNext()) {
				FCPClient client = (FCPClient) (i.next());
				client.addPersistentRequests(v, true);
			}
			globalClient.addPersistentRequests(v, true);
		}
		return (ClientRequest[]) v.toArray(new ClientRequest[v.size()]);
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
	 */
	public void makePersistentGlobalRequest(FreenetURI fetchURI, String expectedMimeType, String persistenceTypeString, String returnTypeString) {
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
					innerMakePersistentGlobalRequest(fetchURI, persistence, returnType, "FProxy:"+fetchURI.toString(false), returnFilename, returnTempFilename);
					return;
				} catch (IdentifierCollisionException e1) {
					// FIXME maybe use DateFormat
					try {
						innerMakePersistentGlobalRequest(fetchURI, persistence, returnType, "FProxy ("+System.currentTimeMillis()+")", returnFilename, returnTempFilename);
						return;
					} catch (IdentifierCollisionException e2) {
						while(true) {
							byte[] buf = new byte[8];
							try {
								core.random.nextBytes(buf);
								String id = "FProxy:"+Base64.encode(buf);
								innerMakePersistentGlobalRequest(fetchURI, persistence, returnType, id, returnFilename, returnTempFilename);
								return;
							} catch (IdentifierCollisionException e3) {};
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
		String extAdd = (ext == null ? "" : "." + ext);
		String preferred = uri.getPreferredFilename();
		File f = new File(core.getDownloadDir(), preferred + extAdd);
		File f1 = new File(core.getDownloadDir(), preferred + ".freenet-tmp");
		int x = 0;
		while(f.exists() || f1.exists()) {
			f = new File(core.getDownloadDir(), preferred + "-" + x + extAdd);
			f1 = new File(core.getDownloadDir(), preferred + "-" + x + extAdd + ".freenet-tmp");
		}
		return f;
	}

	private void innerMakePersistentGlobalRequest(FreenetURI fetchURI, boolean persistRebootOnly, short returnType, String id, File returnFilename, 
			File returnTempFilename) throws IdentifierCollisionException {
		ClientGet cg = 
			new ClientGet(globalClient, fetchURI, defaultFetchContext.localRequestOnly, 
					defaultFetchContext.ignoreStore, QUEUE_MAX_RETRIES, QUEUE_MAX_RETRIES,
					QUEUE_MAX_DATA_SIZE, returnType, persistRebootOnly, id, Integer.MAX_VALUE,
					RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, returnFilename, returnTempFilename);
		// Register before starting, because it may complete immediately, and if it does,
		// we may end up with it not being removable because it wasn't registered!
		if(cg.isPersistentForever())
			forceStorePersistentRequests();
		cg.start();
	}

	/**
	 * Start requests which were not started immediately because it might have taken
	 * some time to start them.
	 */
	public void finishStart() {
		this.globalClient.finishStart();
		
		FCPClient[] clients;
		synchronized(this) {
			clients = (FCPClient[]) clientsByName.values().toArray(new FCPClient[clientsByName.size()]);
		}
		
		for(int i=0;i<clients.length;i++) {
			clients[i].finishStart();
		}
		
		if(enablePersistentDownloads)
			startPersister();
		canStartPersister = true;
	}
	
	
	/**
	 * Returns the global FCP client.
	 * 
	 * @return The global FCP client
	 */
	public FCPClient getGlobalClient() {
		return globalClient;
	}
	
}
