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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.tanukisoftware.wrapper.WrapperManager;

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
	private static boolean ssl = false;
	public final boolean enabled;
	String bindTo;
	private String allowedHosts;
	AllowedHosts allowedHostsFullAccess;
	final WeakHashMap<String, FCPClient> clientsByName;
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
	final FetchContext defaultFetchContext;
	public InsertContext defaultInsertContext;
	public static final int QUEUE_MAX_RETRIES = -1;
	public static final long QUEUE_MAX_DATA_SIZE = Long.MAX_VALUE;
	private boolean canStartPersister = false;
	private boolean assumeDownloadDDAIsAllowed;
	private boolean assumeUploadDDAIsAllowed;
	private boolean hasFinishedStart;
	
	private void startPersister() {
		node.executor.execute(persister = new FCPServerPersister(), "FCP request persister");
	}

	private void killPersister() {
		persister.kill();
		persister = null;
	}

	public FCPServer(String ipToBindTo, String allowedHosts, String allowedHostsFullAccess, int port, Node node, NodeClientCore core, boolean persistentDownloadsEnabled, String persistentDownloadsDir, long persistenceInterval, boolean isEnabled, boolean assumeDDADownloadAllowed, boolean assumeDDAUploadAllowed) throws IOException, InvalidConfigValueException {
		this.bindTo = ipToBindTo;
		this.allowedHosts=allowedHosts;
		this.allowedHostsFullAccess = new AllowedHosts(allowedHostsFullAccess);
		this.persistenceInterval = persistenceInterval;
		this.port = port;
		this.enabled = isEnabled;
		this.enablePersistentDownloads = persistentDownloadsEnabled;
		setPersistentDownloadsFile(new File(persistentDownloadsDir));
		this.node = node;
		this.core = core;
		this.assumeDownloadDDAIsAllowed = assumeDDADownloadAllowed;
		this.assumeUploadDDAIsAllowed = assumeDDAUploadAllowed;
		clientsByName = new WeakHashMap<String, FCPClient>();
		
		// This one is only used to get the default settings. Individual FCP conns
		// will make their own.
		HighLevelSimpleClient client = core.makeClient((short)0);
		defaultFetchContext = client.getFetchContext();
		defaultInsertContext = client.getInsertContext(false);
		
		globalClient = new FCPClient("Global Queue", this, null, true, null);
		
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		if(enabled && enablePersistentDownloads) {
			loadPersistentRequests();
		} else {
			Logger.error(this, "Not loading persistent requests: enabled="+enabled+" enable persistent downloads="+enablePersistentDownloads);
		}
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

	static class FCPPortNumberCallback extends IntCallback  {

		private final NodeClientCore node;
		
		FCPPortNumberCallback(NodeClientCore node) {
			this.node = node;
		}
		
		public Integer get() {
			return node.getFCPServer().port;
		}

		public void set(Integer val) throws InvalidConfigValueException {
			if (!get().equals(val)) {
				throw new InvalidConfigValueException("Cannot change FCP port number on the fly");
			}
		}

		public boolean isReadOnly() {
			return true;
		}
	}
	
	static class FCPEnabledCallback extends BooleanCallback {

		final NodeClientCore node;
		
		FCPEnabledCallback(NodeClientCore node) {
			this.node = node;
		}
		
		public Boolean get() {
			return node.getFCPServer().enabled;
		}
//TODO: Allow it
		public void set(Boolean val) throws InvalidConfigValueException {
			if (!get().equals(val)) {
				throw new InvalidConfigValueException(l10n("cannotStartOrStopOnTheFly"));
			}
		}

		public boolean isReadOnly() {
			return true;
		}
	}

	static class FCPSSLCallback extends BooleanCallback {

		public Boolean get() {
			return ssl;
		}

		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			if(!SSL.available()) {
				throw new InvalidConfigValueException("Enable SSL support before use ssl with FCP");
			}
			ssl = val;
			throw new InvalidConfigValueException("Cannot change SSL on the fly, please restart freenet");
		}

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
	
	static class FCPAllowedHostsCallback extends StringCallback  {

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

	static class FCPAllowedHostsFullAccessCallback extends StringCallback  {
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

	static class PersistentDownloadsEnabledCallback extends BooleanCallback  {
		
		FCPServer server;
		
		public Boolean get() {
			return server.persistentDownloadsEnabled();
		}
		
		public void set(Boolean set) {
			if(server.persistentDownloadsEnabled() != set)
				server.setPersistentDownloadsEnabled(set);
		}
		
	}

	static class PersistentDownloadsFileCallback extends StringCallback  {
		
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

	static class PersistentDownloadsIntervalCallback extends LongCallback  {
		
		FCPServer server;
		
		public Long get() {
			return server.persistenceInterval;
		}
		
		public void set(Long value) {
			server.persistenceInterval = value;
			FCPServerPersister p = server.persister;
			if(p != null) {
				synchronized(p) {
					p.notifyAll();
				}
			}
		}
	}
	
	static class AssumeDDADownloadIsAllowedCallback extends BooleanCallback {
		FCPServer server;

		public Boolean get() {
			return server.assumeDownloadDDAIsAllowed;
		}
		
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			server.assumeDownloadDDAIsAllowed = val;
		}
	}
	
	static class AssumeDDAUploadIsAllowedCallback extends BooleanCallback {
		FCPServer server;

		public Boolean get() {
			return server.assumeUploadDDAIsAllowed;
		}
		
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			server.assumeUploadDDAIsAllowed = val;
		}
	}

	
	public static FCPServer maybeCreate(Node node, NodeClientCore core, Config config) throws IOException, InvalidConfigValueException {
		SubConfig fcpConfig = new SubConfig("fcp", config);
		short sortOrder = 0;
		fcpConfig.register("enabled", true, sortOrder++, true, false, "FcpServer.isEnabled", "FcpServer.isEnabledLong", new FCPEnabledCallback(core));
		fcpConfig.register("ssl", false, sortOrder++, true, true, "FcpServer.ssl", "FcpServer.sslLong", new FCPSSLCallback());
		fcpConfig.register("port", FCPServer.DEFAULT_FCP_PORT /* anagram of 1984, and 1000 up from old number */, 2, true, true, "FcpServer.portNumber", "FcpServer.portNumberLong", new FCPPortNumberCallback(core));
		fcpConfig.register("bindTo", NetworkInterface.DEFAULT_BIND_TO, sortOrder++, true, true, "FcpServer.bindTo", "FcpServer.bindToLong", new FCPBindtoCallback(core));
		fcpConfig.register("allowedHosts", NetworkInterface.DEFAULT_BIND_TO, sortOrder++, true, true, "FcpServer.allowedHosts", "FcpServer.allowedHostsLong", new FCPAllowedHostsCallback(core));
		fcpConfig.register("allowedHostsFullAccess", NetworkInterface.DEFAULT_BIND_TO, sortOrder++, true, true, "FcpServer.allowedHostsFullAccess", "FcpServer.allowedHostsFullAccessLong", new FCPAllowedHostsFullAccessCallback(core));
		PersistentDownloadsEnabledCallback cb1;
		PersistentDownloadsFileCallback cb2;
		PersistentDownloadsIntervalCallback cb3;
		fcpConfig.register("persistentDownloadsEnabled", true, sortOrder++, true, true, "FcpServer.enablePersistentDownload", "FcpServer.enablePersistentDownloadLong", cb1 = new PersistentDownloadsEnabledCallback());
		fcpConfig.register("persistentDownloadsFile", "downloads.dat", sortOrder++, true, false, "FcpServer.filenameToStorePData", "FcpServer.filenameToStorePDataLong", cb2 = new PersistentDownloadsFileCallback());
		fcpConfig.register("persistentDownloadsInterval", (5*60*1000), sortOrder++, true, false, "FcpServer.intervalBetweenWrites", "FcpServer.intervalBetweenWritesLong", cb3 = new PersistentDownloadsIntervalCallback());
		String persistentDownloadsDir = fcpConfig.getString("persistentDownloadsFile");
		boolean persistentDownloadsEnabled = fcpConfig.getBoolean("persistentDownloadsEnabled");		
		long persistentDownloadsInterval = fcpConfig.getLong("persistentDownloadsInterval");
		
		AssumeDDADownloadIsAllowedCallback cb4;
		AssumeDDAUploadIsAllowedCallback cb5;
		fcpConfig.register("assumeDownloadDDAIsAllowed", false, sortOrder++, true, false, "FcpServer.assumeDownloadDDAIsAllowed", "FcpServer.assumeDownloadDDAIsAllowedLong", cb4 = new AssumeDDADownloadIsAllowedCallback());
		fcpConfig.register("assumeUploadDDAIsAllowed", false, sortOrder++, true, false, "FcpServer.assumeUploadDDAIsAllowed", "FcpServer.assumeUploadDDAIsAllowedLong", cb5 = new AssumeDDAUploadIsAllowedCallback());

		if(SSL.available()) {
			ssl = fcpConfig.getBoolean("ssl");
		}
		
		FCPServer fcp = new FCPServer(fcpConfig.getString("bindTo"), fcpConfig.getString("allowedHosts"), fcpConfig.getString("allowedHostsFullAccess"), fcpConfig.getInt("port"), node, core, persistentDownloadsEnabled, persistentDownloadsDir, persistentDownloadsInterval, fcpConfig.getBoolean("enabled"), fcpConfig.getBoolean("assumeDownloadDDAIsAllowed"), fcpConfig.getBoolean("assumeUploadDDAIsAllowed"));
		
		if(fcp != null) {
			cb1.server = fcp;
			cb2.server = fcp;
			cb3.server = fcp;
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

	public void setPersistentDownloadsEnabled(boolean set) {
		synchronized(this) {
			if(enablePersistentDownloads == set) return;
			enablePersistentDownloads = set;
		}
		synchronized(persistenceSync) {
			if(set) {
				if(!haveLoadedPersistentRequests)
					loadPersistentRequests();
				if(canStartPersister)
					startPersister();
			} else {
				killPersister();
			}
		}
	}

	public synchronized boolean persistentDownloadsEnabled() {
		return enablePersistentDownloads;
	}

	public FCPClient registerClient(String name, NodeClientCore core, FCPConnectionHandler handler) {
		FCPClient oldClient;
		synchronized(this) {
			oldClient = clientsByName.get(name);
			if(oldClient == null) {
				// Create new client
				FCPClient client = new FCPClient(name, this, handler, false, null);
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
		    freenet.support.Logger.OSThread.logPID(this);
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
		LinkedList<Bucket> toFree = null;
		try {
			synchronized(persistenceSync) {
				toFree = core.persistentTempBucketFactory.grabBucketsToFree();
                                
				File compressedTemp = new File(persistentDownloadsTempFile+".gz");
				File compressedFinal = new File(persistentDownloadsFile.toString()+".gz");
				compressedTemp.delete();
                
				FileOutputStream fos = null;
				BufferedOutputStream bos = null;
				GZIPOutputStream gos = null;
				OutputStreamWriter osw = null;
				BufferedWriter w = null;
				
				try {
					fos = new FileOutputStream(compressedTemp);
					bos = new BufferedOutputStream(fos);
					gos = new GZIPOutputStream(bos);
					osw = new OutputStreamWriter(gos, "UTF-8");
					w = new BufferedWriter(osw);
					w.write(Integer.toString(persistentRequests.length)+ '\n');
					for (ClientRequest persistentRequest : persistentRequests)
						persistentRequest.write(w);
					
					w.flush();
					w.close();
					FileUtil.renameTo(compressedTemp, compressedFinal);
				} catch (IOException e) {
					Logger.error(this, "Cannot write persistent requests to disk: "+e);
				} finally {
					Closer.close(w);
					Closer.close(osw);
					Closer.close(gos);
					Closer.close(bos);
					Closer.close(fos);
				}
			}
			if(logMINOR) Logger.minor(this, "Stored persistent requests");
		} finally {
			if(toFree != null) {
				long freedBuckets = 0;
				for (Bucket current : toFree) {
					try {
						current.free();
						freedBuckets++;
					} catch(Throwable t) {
						try {
							System.err.println("Caught " + t + " trying to free bucket " + current);
							t.printStackTrace();
						} catch(Throwable t1) { /* ignore */ }
					}
				}
				// Help it to be collected
				toFree.clear();
				toFree = null;
				if(logMINOR)
					Logger.minor(this, "We have freed "+freedBuckets+" persistent buckets");
			}
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

	private ClientRequest[] getPersistentRequests() {
		List<ClientRequest> v = new ArrayList<ClientRequest>();
		synchronized(this) {
			Iterator<FCPClient> i = clientsByName.values().iterator();
			while(i.hasNext()) {
				FCPClient client = (i.next());
				client.addPersistentRequests(v, true);
			}
			globalClient.addPersistentRequests(v, true);
		}
		return v.toArray(new ClientRequest[v.size()]);
	}

	public ClientRequest[] getGlobalRequests() {
		List<ClientRequest> v = new ArrayList<ClientRequest>();
		globalClient.addPersistentRequests(v, false);
		return v.toArray(new ClientRequest[v.size()]);
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

	private void innerMakePersistentGlobalRequest(FreenetURI fetchURI, boolean persistRebootOnly, short returnType, String id, File returnFilename, 
			File returnTempFilename) throws IdentifierCollisionException, NotAllowedException {
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
			clients = clientsByName.values().toArray(new FCPClient[clientsByName.size()]);
		}
		
		for (FCPClient client : clients) {
			client.finishStart();
		}
		
		if(enablePersistentDownloads)
			startPersister();
		canStartPersister = true;
		hasFinishedStart = true;
	}
	
	
	/**
	 * Returns the global FCP client.
	 * 
	 * @return The global FCP client
	 */
	public FCPClient getGlobalClient() {
		return globalClient;
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
		if(globalClient.setRequestCompletionCallback(cb) != null)
			Logger.error(this, "Replacing request completion callback "+cb, new Exception("error"));
	}
	
}
