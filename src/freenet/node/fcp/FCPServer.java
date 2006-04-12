package freenet.node.fcp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.Vector;
import java.util.WeakHashMap;

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
import freenet.node.Node;
import freenet.support.Logger;

/**
 * FCP server process.
 */
public class FCPServer implements Runnable {

	final ServerSocket sock;
	final Node node;
	final int port;
	final boolean enabled;
	final String bindTo;
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
	
	private void startPersister() {
		Thread t = new Thread(persister = new FCPServerPersister(), "FCP request persistence handler");
		t.setDaemon(true);
		t.start();
	}

	private void killPersister() {
		persister.kill();
		persister = null;
	}

	public FCPServer(String ipToBindTo, int port, Node node, boolean persistentDownloadsEnabled, String persistentDownloadsDir, long persistenceInterval) throws IOException, InvalidConfigValueException {
		this.bindTo = ipToBindTo;
		this.persistenceInterval = persistenceInterval;
		this.port = port;
		this.enabled = true;
		this.sock = new ServerSocket(port, 0, InetAddress.getByName(bindTo));
		this.node = node;
		clientsByName = new WeakHashMap();
		// This one is only used to get the default settings. Individual FCP conns
		// will make their own.
		HighLevelSimpleClient client = node.makeClient((short)0);
		defaultFetchContext = client.getFetcherContext();
		defaultInsertContext = client.getInserterContext();
		Thread t = new Thread(this, "FCP server");
		this.enablePersistentDownloads = persistentDownloadsEnabled;
		globalClient = new FCPClient("Global Queue", this, null, true);
		setPersistentDownloadsFile(new File(persistentDownloadsDir));
		t.setDaemon(true);
		t.start();
		if(enablePersistentDownloads) {
			loadPersistentRequests();
			startPersister();
		}
	}
	
	public void run() {
		while(true) {
			try {
				realRun();
			} catch (IOException e) {
				Logger.minor(this, "Caught "+e, e);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
	}

	private void realRun() throws IOException {
		// Accept a connection
		Socket s = sock.accept();
		new FCPConnectionHandler(s, this);
	}

	static class FCPPortNumberCallback implements IntCallback {

		private final Node node;
		
		FCPPortNumberCallback(Node node) {
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

		final Node node;
		
		FCPEnabledCallback(Node node) {
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

		final Node node;
		
		FCPBindtoCallback(Node node) {
			this.node = node;
		}
		
		public String get() {
			return node.getFCPServer().bindTo;
		}

//TODO: Allow it
		public void set(String val) throws InvalidConfigValueException {
			if(val.equals(get())) {
				throw new InvalidConfigValueException("Cannot change the ip address the server is binded to on the fly");
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
	
	public static FCPServer maybeCreate(Node node, Config config) throws IOException, InvalidConfigValueException {
		SubConfig fcpConfig = new SubConfig("fcp", config);
		fcpConfig.register("enabled", true, 2, true, "Is FCP server enabled ?", "Is FCP server enabled ?", new FCPEnabledCallback(node));
		fcpConfig.register("port", 9481 /* anagram of 1984, and 1000 up from old number */,
				2, true, "FCP port number", "FCP port number", new FCPPortNumberCallback(node));
		fcpConfig.register("bindTo", "127.0.0.1", 2, true, "Ip address to bind to", "Ip address to bind the FCP server to", new FCPBindtoCallback(node));
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
		
		fcpConfig.register("persistentDownloadsInterval", (long)(5*60*1000), 5, true, "Interval between writing persistent downloads to disk", "Interval between writing persistent downloads to disk",
				cb3 = new PersistentDownloadsIntervalCallback());
		
		long persistentDownloadsInterval = fcpConfig.getLong("persistentDownloadsInterval");
		
		FCPServer fcp;
		if(fcpConfig.getBoolean("enabled")){
			Logger.normal(node, "Starting FCP server on "+fcpConfig.getString("bindTo")+":"+fcpConfig.getInt("port")+".");
			fcp = new FCPServer(fcpConfig.getString("bindTo"), fcpConfig.getInt("port"), node, persistentDownloadsEnabled, persistentDownloadsDir, persistentDownloadsInterval);
			node.setFCPServer(fcp);	
			
			if(fcp != null) {
				cb1.server = fcp;
				cb2.server = fcp;
				cb3.server = fcp;
			}
		}else{
			Logger.normal(node, "Not starting FCP server as it's disabled");
			fcp = null;
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
			throw new InvalidConfigValueException("Invalid filename for downloads list: is a directory");
		if(f.isFile() && !(f.canRead() && f.canWrite()))
			throw new InvalidConfigValueException("File exists but cannot be read");
		File parent = f.getParentFile();
		if(parent != null && !parent.exists())
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

	public FCPClient registerClient(String name, Node node, FCPConnectionHandler handler) {
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
				synchronized(this) {
					if(killed) return;
					long startTime = System.currentTimeMillis();
					long now;
					while((now = System.currentTimeMillis()) < startTime + persistenceInterval && !storeNow) {
						try {
							long wait = Math.max((startTime + persistenceInterval) - now, Integer.MAX_VALUE);
							if(wait > 0)
								wait(Math.min(wait, 5000));
						} catch (InterruptedException e) {
							// Ignore
						}
						if(killed) return;
					}
					storeNow = false;
				}
				try {
					storePersistentRequests();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				}
			}
		}
		
	}

	public void forceStorePersistentRequests() {
		Logger.minor(this, "Forcing store persistent requests");
		if(persister != null) {
			persister.force();
		} else {
			Logger.error(this, "Persister not running, cannot store persistent requests");
		}
	}
	
	/** Store all persistent requests to disk */
	public void storePersistentRequests() {
		Logger.minor(this, "Storing persistent requests");
		ClientRequest[] persistentRequests = getPersistentRequests();
		Logger.minor(this, "Persistent requests count: "+persistentRequests.length);
		synchronized(persistenceSync) {
			try {
				FileOutputStream fos = new FileOutputStream(persistentDownloadsTempFile);
				BufferedOutputStream bos = new BufferedOutputStream(fos);
				OutputStreamWriter osw = new OutputStreamWriter(bos);
				BufferedWriter w = new BufferedWriter(osw);
				w.write(Integer.toString(persistentRequests.length)+"\n");
				for(int i=0;i<persistentRequests.length;i++)
					persistentRequests[i].write(w);
				w.close();
				if(!persistentDownloadsTempFile.renameTo(persistentDownloadsFile)) {
					Logger.minor(this, "Rename failed");
					persistentDownloadsFile.delete();
					if(!persistentDownloadsTempFile.renameTo(persistentDownloadsFile)) {
						Logger.error(this, "Could not rename persisted requests temp file "+persistentDownloadsTempFile+" to "+persistentDownloadsFile);
					}
				}
			} catch (IOException e) {
				Logger.error(this, "Cannot write persistent requests to disk: "+e);
			}
		}
		Logger.minor(this, "Stored persistent requests");
	}

	private void loadPersistentRequests() {
		synchronized(persistenceSync) {
			FileInputStream fis;
			try {
				fis = new FileInputStream(persistentDownloadsFile);
			} catch (FileNotFoundException e) {
				Logger.normal(this, "Not reading any persistent requests from disk because no file exists");
				return;
			}
			try {
				BufferedInputStream bis = new BufferedInputStream(fis);
				InputStreamReader ris = new InputStreamReader(bis);
				BufferedReader br = new BufferedReader(ris);
				String r = br.readLine();
				int count;
				try {
					count = Integer.parseInt(r);
				} catch (NumberFormatException e) {
					Logger.error(this, "Corrupt persistent downloads file: "+persistentDownloadsFile);
					return;
				}
				for(int i=0;i<count;i++) {
					ClientRequest.readAndRegister(br, this);
				}
			} catch (IOException e) {
				Logger.error(this, "Error reading persistent downloads file: "+persistentDownloadsFile+" : "+e, e);
				return;
			} finally {
				try {
					fis.close();
				} catch (IOException e1) {
					Logger.error(this, "Error closing: "+e1, e1);
				}
			}
			return;
		}
	}

	private ClientRequest[] getPersistentRequests() {
		Vector v = new Vector();
		synchronized(this) {
			Iterator i = clientsByName.values().iterator();
			while(i.hasNext()) {
				FCPClient client = (FCPClient) (i.next());
				client.addPersistentRequests(v);
			}
			globalClient.addPersistentRequests(v);
		}
		return (ClientRequest[]) v.toArray(new ClientRequest[v.size()]);
	}

}
