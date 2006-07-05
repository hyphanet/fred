package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import freenet.config.BooleanCallback;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.crypt.DummyRandomSource;
import freenet.io.NetworkInterface;
import freenet.node.Node;
import freenet.support.BucketFactory;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.FileLoggerHook.IntervalParseException;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.TempBucketFactory;

public class SimpleToadletServer implements ToadletContainer, Runnable {
	
	private static class ToadletElement {
		public ToadletElement(Toadlet t2, String urlPrefix) {
			t = t2;
			prefix = urlPrefix;
		}
		Toadlet t;
		String prefix;
	}

	final int port;
	final String bindTo;
	String allowedHosts;
	final BucketFactory bf;
	final NetworkInterface networkInterface;
	private final LinkedList toadlets;
	private String cssName;
	private Thread myThread;
	private boolean advancedDarknetEnabled;
	
	static final int DEFAULT_FPROXY_PORT = 8888;
	
	class FProxyPortCallback implements IntCallback {
		
		public int get() {
			return port;
		}
		
		public void set(int newPort) throws InvalidConfigValueException {
			if(port != newPort)
				throw new InvalidConfigValueException("Cannot change FProxy port number on the fly");
			// FIXME
		}
	}
	
	class FProxyBindtoCallback implements StringCallback {
		
		public String get() {
			return bindTo;
		}
		
		public void set(String bindTo) throws InvalidConfigValueException {
			if(!bindTo.equals(get()))
				throw new InvalidConfigValueException("Cannot change FProxy bind address on the fly");
		}
	}
	
	class FProxyAllowedHostsCallback implements StringCallback {
	
		public String get() {
			return allowedHosts;
		}
		
		public void set(String allowedHosts) {
			if (!allowedHosts.equals(get())) {
				networkInterface.setAllowedHosts(allowedHosts);
				SimpleToadletServer.this.allowedHosts = allowedHosts;
			}
		}
		
	}
	
	class FProxyCSSNameCallback implements StringCallback {
		
		public String get() {
			return cssName;
		}
		
		public void set(String CSSName) throws InvalidConfigValueException {
			if(CSSName.indexOf(':') != -1 || CSSName.indexOf('/') != -1)
				throw new InvalidConfigValueException("CSS name must not contain slashes or colons!");
			cssName = CSSName;
		}
	}
	
	class FProxyEnabledCallback implements BooleanCallback {
		
		public boolean get() {
			synchronized(SimpleToadletServer.this) {
				return myThread != null;
			}
		}
		public void set(boolean val) throws InvalidConfigValueException {
			if(val == get()) return;
			synchronized(SimpleToadletServer.this) {
				if(val) {
					// Start it
					myThread = new Thread(SimpleToadletServer.this, "SimpleToadletServer");
				} else {
					myThread.interrupt();
					myThread = null;
					return;
				}
			}
			myThread.setDaemon(true);
			myThread.start();
		}
	}
	
	private static class FProxyAdvancedDarknetEnabledCallback implements BooleanCallback {
		
		private final SimpleToadletServer ts;
		
		FProxyAdvancedDarknetEnabledCallback(SimpleToadletServer ts){
			this.ts = ts;
		}
		
		public boolean get() {
			return ts.isAdvancedDarknetEnabled();
		}
		
		public void set(boolean val) throws InvalidConfigValueException {
			if(val == get()) return;
				ts.enableAdvancedDarknet(val);
		}
	}
	
	/**
	 * Create a SimpleToadletServer, using the settings from the SubConfig (the fproxy.*
	 * config).
	 */
	public SimpleToadletServer(SubConfig fproxyConfig, Node node) throws IOException, InvalidConfigValueException {
		
		fproxyConfig.register("enabled", true, 1, true, "Enable FProxy?", "Whether to enable FProxy and related HTTP services",
				new FProxyEnabledCallback());
		
		boolean enabled = fproxyConfig.getBoolean("enabled");
		
		List themes = new ArrayList();
		try {
			URL url = getClass().getResource("staticfiles/themes/");
			URLConnection urlConnection = url.openConnection();
			if (url.getProtocol().equals("file")) {
				File themesDirectory = new File(URLDecoder.decode(url.getPath(), "ISO-8859-1").replaceAll("\\|", ":"));
				File[] themeDirectories = themesDirectory.listFiles();
				for (int themeIndex = 0; themeDirectories != null && themeIndex < themeDirectories.length; themeIndex++) {
					File themeDirectory = themeDirectories[themeIndex];
					if (themeDirectory.isDirectory() && !themeDirectory.getName().startsWith(".")) {
						themes.add(themeDirectory.getName());
					}
				}	
			} else if (urlConnection instanceof JarURLConnection) {
				JarURLConnection jarUrlConnection = (JarURLConnection) urlConnection;
				JarFile jarFile = jarUrlConnection.getJarFile();
				Enumeration entries = jarFile.entries();
				while (entries.hasMoreElements()) {
					JarEntry entry = (JarEntry) entries.nextElement();
					String name = entry.getName();
					if (name.startsWith("freenet/clients/http/staticfiles/themes/")) {
						name = name.substring("freenet/clients/http/staticfiles/themes/".length());
						if (name.indexOf('/') != -1) {
							String themeName = name.substring(0, name.indexOf('/'));
							if (!themes.contains(themeName)) {
								themes.add(themeName);
							}
						}
					}
				}
			}
		} catch (IOException ioe1) {
			Logger.error(this, "error creating list of themes", ioe1);
		} catch (NullPointerException npe) {
			Logger.error(this, "error creating list of themes", npe);
		} finally {
			if (!themes.contains("clean")) {
				themes.add("clean");
			}
		}
		
		fproxyConfig.register("port", DEFAULT_FPROXY_PORT, 2, true, "FProxy port number", "FProxy port number",
				new FProxyPortCallback());
		fproxyConfig.register("bindTo", "127.0.0.1", 2, true, "IP address to bind to", "IP address to bind to",
				new FProxyBindtoCallback());
		fproxyConfig.register("allowedHosts", "127.0.0.1", 2, true, "Allowed hosts", "Hostnames or IP addresses that are allowed to connect to FProxy. May be a comma-separated list of hostnames, single IPs and even CIDR masked IPs like 192.168.0.0/24",
				new FProxyAllowedHostsCallback());
		fproxyConfig.register("css", "clean", 1, false, "CSS Name", "Name of the CSS FProxy should use "+themes.toString(),
				new FProxyCSSNameCallback());
		fproxyConfig.register("advancedDarknetEnabled", false, 1, false, "Enable Advanced Darknet?", "Whether to show or not informations meant for advanced users/devs. This setting should be turned to false in most cases.",
				new FProxyAdvancedDarknetEnabledCallback(this));

		this.bf = node.tempBucketFactory;
		port = fproxyConfig.getInt("port");
		bindTo = fproxyConfig.getString("bindTo");
		allowedHosts = fproxyConfig.getString("allowedHosts");
		cssName = fproxyConfig.getString("css");
		if(cssName.indexOf(':') != -1 || cssName.indexOf('/') != -1)
			throw new InvalidConfigValueException("CSS name must not contain slashes or colons!");
		this.advancedDarknetEnabled = fproxyConfig.getBoolean("advancedDarknetEnabled");
		
		toadlets = new LinkedList();
		node.setToadletContainer(this); // even if not enabled, because of config
		
		this.networkInterface = new NetworkInterface(port, this.bindTo, this.allowedHosts);
		if(!enabled) {
			Logger.normal(node, "Not starting FProxy as it's disabled");
			System.out.println("Not starting FProxy as it's disabled");
		} else {
			myThread = new Thread(this, "SimpleToadletServer");
			myThread.setDaemon(true);
		}
	}
	
	public SimpleToadletServer(int i, String newbindTo, String allowedHosts, BucketFactory bf, String cssName) throws IOException {
		this.port = i;
		this.bindTo = newbindTo;
		this.allowedHosts = allowedHosts;
		this.bf = bf;
		this.networkInterface = new NetworkInterface(port, this.bindTo, this.allowedHosts);
		toadlets = new LinkedList();
		this.cssName = cssName;
	}

	public void start() {
		if(myThread != null) {
			myThread.start();
			Logger.normal(this, "Starting FProxy on "+bindTo+":"+port);
			System.out.println("Starting FProxy on "+bindTo+":"+port);
		}
	}
	
	public void register(Toadlet t, String urlPrefix, boolean atFront) {
		ToadletElement te = new ToadletElement(t, urlPrefix);
		if(atFront) toadlets.addFirst(te);
		else toadlets.addLast(te);
		t.container = this;
	}

	public Toadlet findToadlet(URI uri) {
		Iterator i = toadlets.iterator();
		while(i.hasNext()) {
			ToadletElement te = (ToadletElement) i.next();
			
			if(uri.getPath().startsWith(te.prefix))
				return te.t;
		}
		return null;
	}
	
	public static void main(String[] args) throws IOException, IntervalParseException {
        File logDir = new File("logs-toadlettest");
        logDir.mkdir();
        FileLoggerHook logger = new FileLoggerHook(true, new File(logDir, "test-1111").getAbsolutePath(), 
        		"d (c, t, p): m", "MMM dd, yyyy HH:mm:ss:SSS", Logger.MINOR, false, true, 
        		1024*1024*1024 /* 1GB of old compressed logfiles */);
        logger.setInterval("5MINUTES");
        Logger.setupChain();
        Logger.globalSetThreshold(Logger.MINOR);
        Logger.globalAddHook(logger);
        logger.start();
		SimpleToadletServer server = new SimpleToadletServer(1111, "127.0.0.1", "127.0.0.1", new TempBucketFactory(new FilenameGenerator(new DummyRandomSource(), true, new File("temp-test"), "test-temp-")), "aqua");
		server.register(new TrivialToadlet(null), "", true);
		server.start();
		System.out.println("Bound to port 1111.");
		while(true) {
			try {
				Thread.sleep(100000);
			} catch (InterruptedException e) {
				// Ignore
			}
		}
	}

	public void run() {
		try {
			networkInterface.setSoTimeout(500);
		} catch (SocketException e1) {
			Logger.error(this, "Could not set so-timeout to 500ms; on-the-fly disabling of the interface will not work");
		}
		while(true) {
			synchronized(this) {
				if(myThread == null) return;
			}
			try {
				Socket conn = networkInterface.accept();
				Logger.minor(this, "Accepted connection");
				SocketHandler sh = new SocketHandler(conn);
				sh.start();
			} catch (SocketTimeoutException e) {
				// Go around again, this introduced to avoid blocking forever when told to quit
			} 
		}
	}
	
	public class SocketHandler implements Runnable {

		Socket sock;
		
		public SocketHandler(Socket conn) {
			this.sock = conn;
		}

		void start() {
			Thread t = new Thread(this, "SimpleToadletServer$SocketHandler");
			t.setDaemon(true);
			t.start();
		}
		
		public void run() {
			Logger.minor(this, "Handling connection");
			ToadletContextImpl.handle(sock, SimpleToadletServer.this, bf);
			Logger.minor(this, "Handled connection");
		}

	}

	public String getCSSName() {
		return this.cssName;
	}

	public void setCSSName(String name) {
		this.cssName = name;
	}

	public synchronized boolean isAdvancedDarknetEnabled() {
		return this.advancedDarknetEnabled;
	}
	
	public synchronized void enableAdvancedDarknet(boolean b){
		advancedDarknetEnabled = b;
	}
}
