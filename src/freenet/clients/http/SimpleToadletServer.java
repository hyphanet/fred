package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.config.BooleanCallback;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.crypt.DummyRandomSource;
import freenet.node.Node;
import freenet.support.BucketFactory;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.FileLoggerHook.IntervalParseException;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.TempBucketFactory;

public class SimpleToadletServer implements ToadletContainer, Runnable {
	
	public class ToadletElement {
		public ToadletElement(Toadlet t2, String urlPrefix) {
			t = t2;
			prefix = urlPrefix;
		}
		Toadlet t;
		String prefix;
	}

	final int port;
	final String bindTo;
	final BucketFactory bf;
	private final ServerSocket sock;
	private final LinkedList toadlets;
	private String cssName;
	private Thread myThread;
	
	static final int DEFAULT_FPROXY_PORT = 8888;
	
	class FproxyPortCallback implements IntCallback {
		
		public int get() {
			return port;
		}
		
		public void set(int newPort) throws InvalidConfigValueException {
			if(port != newPort)
				throw new InvalidConfigValueException("Cannot change fproxy port number on the fly");
			// FIXME
		}
	}
	
	class FproxyBindtoCallback implements StringCallback {
		
		public String get() {
			return bindTo;
		}
		
		public void set(String bindTo) throws InvalidConfigValueException {
			if(bindTo != get())
				throw new InvalidConfigValueException("Cannot change fproxy bind address on the fly");
		}
	}
	
	class FproxyCSSNameCallback implements StringCallback {
		
		public String get() {
			return cssName;
		}
		
		public void set(String CSSName) throws InvalidConfigValueException {
			if(CSSName.indexOf(':') != -1 || CSSName.indexOf('/') != -1)
				throw new InvalidConfigValueException("CSS name must not contain slashes or colons!");
			cssName = CSSName;
		}
	}
	
	class FproxyEnabledCallback implements BooleanCallback {
		
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
					myThread.setDaemon(true);
					myThread.start();
				} else {
					myThread.interrupt();
					myThread = null;
				}
			}
		}
	}
	
	/**
	 * Create a SimpleToadletServer, using the settings from the SubConfig (the fproxy.*
	 * config).
	 */
	public SimpleToadletServer(SubConfig fproxyConfig, Node node) throws IOException, InvalidConfigValueException {
		
		fproxyConfig.register("enabled", true, 1, true, "Enable fproxy?", "Whether to enable fproxy and related HTTP services",
				new FproxyEnabledCallback());
		
		boolean enabled = fproxyConfig.getBoolean("enabled");
		
		fproxyConfig.register("port", DEFAULT_FPROXY_PORT, 2, true, "Fproxy port number", "Fproxy port number",
				new FproxyPortCallback());
		fproxyConfig.register("bindTo", "127.0.0.1", 2, true, "IP address to bind to", "IP address to bind to",
				new FproxyBindtoCallback());
		fproxyConfig.register("css", "clean", 1, true, "CSS Name", "Name of the CSS Fproxy should use",
				new FproxyCSSNameCallback());

		this.bf = node.tempBucketFactory;
		port = fproxyConfig.getInt("port");
		bindTo = fproxyConfig.getString("bindTo");
		cssName = fproxyConfig.getString("css");
		if(cssName.indexOf(':') != -1 || cssName.indexOf('/') != -1)
			throw new InvalidConfigValueException("CSS name must not contain slashes or colons!");
		
		this.sock = new ServerSocket(port, 0, InetAddress.getByName(this.bindTo));
		toadlets = new LinkedList();
		
		node.setToadletContainer(this); // even if not enabled, because of config
		
		if(!enabled) {
			Logger.normal(node, "Not starting Fproxy as it's disabled");
		} else {
			myThread = new Thread(this, "SimpleToadletServer");
			myThread.setDaemon(true);
			myThread.start();
			System.out.println("Starting fproxy on port "+(port));
			Logger.normal(this, "Starting fproxy on "+bindTo+":"+port);
		}
	}
	
	public SimpleToadletServer(int i, String newbindTo, BucketFactory bf, String cssName) throws IOException {
		this.port = i;
		this.bindTo = newbindTo;
		this.bf = bf;
		this.sock = new ServerSocket(port, 0, InetAddress.getByName(this.bindTo));
		toadlets = new LinkedList();
		this.cssName = cssName;
		Thread t = new Thread(this, "SimpleToadletServer");
		t.setDaemon(true);
		t.start();
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
		SimpleToadletServer server = new SimpleToadletServer(1111, "127.0.0.1", new TempBucketFactory(new FilenameGenerator(new DummyRandomSource(), true, new File("temp-test"), "test-temp-")), "aqua");
		server.register(new TrivialToadlet(null), "", true);
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
			sock.setSoTimeout(500);
		} catch (SocketException e1) {
			Logger.error(this, "Could not set so-timeout to 500ms; on-the-fly disabling of the interface will not work");
		}
		while(true) {
			synchronized(this) {
				if(myThread == null) return;
			}
			try {
				Socket conn = sock.accept();
				Logger.minor(this, "Accepted connection");
				new SocketHandler(conn);
			} catch (SocketTimeoutException e) {
				// Go around again, this introduced to avoid blocking forever when told to quit
			} catch (IOException e) {
				Logger.minor(this, "Got IOException accepting conn: "+e, e);
				// Ignore
				continue;
			}
		}
	}
	
	public class SocketHandler implements Runnable {

		Socket sock;
		
		public SocketHandler(Socket conn) {
			this.sock = conn;
			Thread t = new Thread(this);
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
}
