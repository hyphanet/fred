/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.JarURLConnection;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
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

import freenet.clients.http.bookmark.BookmarkManager;
import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.SSL;
import freenet.io.AllowedHosts;
import freenet.io.NetworkInterface;
import freenet.io.SSLNetworkInterface;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.support.Executor;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.StringArray;
import freenet.support.api.BooleanCallback;
import freenet.support.api.BucketFactory;
import freenet.support.api.IntCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.ArrayBucketFactory;

public final class SimpleToadletServer implements ToadletContainer, Runnable {
	
	private static class ToadletElement {
		public ToadletElement(Toadlet t2, String urlPrefix) {
			t = t2;
			prefix = urlPrefix;
		}
		Toadlet t;
		String prefix;
	}

	final int port;
	String bindTo;
	private String allowedHosts;
	final AllowedHosts allowedFullAccess;
	BucketFactory bf;
	NetworkInterface networkInterface;
	private final LinkedList toadlets;
	private String cssName;
	private File cssOverride;
	private Thread myThread;
	private boolean advancedModeEnabled;
	private boolean ssl = false;
	private boolean fProxyJavascriptEnabled;
	private final PageMaker pageMaker;
	private NodeClientCore core;
	private final Executor executor;
	private boolean doRobots;
	public BookmarkManager bookmarkManager;
	private boolean enablePersistentConnections;
	private boolean enableInlinePrefetch;
	
	static boolean isPanicButtonToBeShown;
	public static final int DEFAULT_FPROXY_PORT = 8888;
	
	class FProxySSLCallback extends BooleanCallback  {
		
		public boolean get() {
			return ssl;
		}
		public void set(boolean val) throws InvalidConfigValueException {
			if(val == get()) return;
			if(!SSL.available()) {
				throw new InvalidConfigValueException("Enable SSL support before use ssl with Fproxy");
			}
			ssl = val;
			throw new InvalidConfigValueException("Cannot change SSL on the fly, please restart freenet");
		}
		public boolean isReadOnly() {
			return true;
		}
	}
	
	class FProxyPassthruMaxSize extends IntCallback  {
		
		public int get() {
			return FProxyToadlet.MAX_LENGTH;
		}
		
		public void set(int val) throws InvalidConfigValueException {
			if(val == get()) return;
			FProxyToadlet.MAX_LENGTH = val;
		}
	}

	class FProxyPortCallback extends IntCallback  {
		
		public int get() {
			return port;
		}
		
		public void set(int newPort) throws InvalidConfigValueException {
			if(port != newPort)
				throw new InvalidConfigValueException(L10n.getString("cannotChangePortOnTheFly"));
			// FIXME
		}
		public boolean isReadOnly() {
			return true;
		}
	}
	
	class FProxyBindtoCallback extends StringCallback  {
		
		public String get() {
			return bindTo;
		}
		
		public void set(String bindTo) throws InvalidConfigValueException {
			if(!bindTo.equals(get())) {
				try {
					networkInterface.setBindTo(bindTo, false);
					SimpleToadletServer.this.bindTo = bindTo;
				} catch (IOException e) {
					// This is an advanced option for reasons of reducing clutter,
					// but it is expected to be used by regular users, not devs.
					// So we translate the error messages.
					throw new InvalidConfigValueException(l10n("couldNotChangeBindTo", "error", e.getLocalizedMessage())); 
				}
			}
		}
	}
	
	class FProxyAllowedHostsCallback extends StringCallback  {
	
		public String get() {
			return networkInterface.getAllowedHosts();
		}
		
		public void set(String allowedHosts) {
			if (!allowedHosts.equals(get())) {
				networkInterface.setAllowedHosts(allowedHosts);
			}
		}
	}
	
	class FProxyCSSNameCallback extends StringCallback implements EnumerableOptionCallback {
		
		public String get() {
			return cssName;
		}
		
		public void set(String CSSName) throws InvalidConfigValueException {
			if((CSSName.indexOf(':') != -1) || (CSSName.indexOf('/') != -1))
				throw new InvalidConfigValueException(l10n("illegalCSSName"));
			cssName = CSSName;
			pageMaker.setTheme(cssName);
		}
		
		public void setPossibleValues(String[] val) {
			throw new NullPointerException("Should not happen!");
		}
		
		public String[] getPossibleValues() {
			return StringArray.toArray(pageMaker.getThemes().toArray());
		}
	}
	
	class FProxyCSSOverrideCallback extends StringCallback  {

		public String get() {
			return (cssOverride == null ? "" : cssOverride.toString());
		}

		public void set(String val) throws InvalidConfigValueException {
			if(core == null) return;
			if(val.equals(get()) || val.equals(""))
				cssOverride = null;
			else {
				File tmp = new File(val.trim());
				if(!core.allowUploadFrom(tmp))
					throw new InvalidConfigValueException(l10n("cssOverrideNotInUploads", "filename", tmp.toString()));
				else if(!tmp.canRead() || !tmp.isFile())
					throw new InvalidConfigValueException(l10n("cssOverrideCantRead", "filename", tmp.toString()));
				cssOverride = tmp.getAbsoluteFile();
			}
			pageMaker.setOverride(cssOverride);
		}
	}
	
	class FProxyEnabledCallback extends BooleanCallback  {
		
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
			createFproxy();
			myThread.setDaemon(true);
			myThread.start();
		}
	}
	
	private boolean haveCalledFProxy = false;
	
	public void createFproxy() {
		synchronized(this) {
			if(haveCalledFProxy) return;
			haveCalledFProxy = true;
		}
		bookmarkManager = new BookmarkManager(core);
		try {
			FProxyToadlet.maybeCreateFProxyEtc(core, core.node, core.node.config, this, bookmarkManager);
		} catch (IOException e) {
			Logger.error(this, "Could not start fproxy: "+e, e);
			System.err.println("Could not start fproxy:");
			e.printStackTrace();
		}
	}
	
	private static class FProxyAdvancedModeEnabledCallback extends BooleanCallback  {
		
		private final SimpleToadletServer ts;
		
		FProxyAdvancedModeEnabledCallback(SimpleToadletServer ts){
			this.ts = ts;
		}
		
		public boolean get() {
			return ts.isAdvancedModeEnabled();
		}
		
		public void set(boolean val) throws InvalidConfigValueException {
			if(val == get()) return;
				ts.enableAdvancedMode(val);
		}
	}
	
	private static class FProxyJavascriptEnabledCallback extends BooleanCallback  {
		
		private final SimpleToadletServer ts;
		
		FProxyJavascriptEnabledCallback(SimpleToadletServer ts){
			this.ts = ts;
		}
		
		public boolean get() {
			return ts.isFProxyJavascriptEnabled();
		}
		
		public void set(boolean val) throws InvalidConfigValueException {
			if(val == get()) return;
				ts.enableFProxyJavascript(val);
		}
	}
	
	public synchronized void setCore(NodeClientCore core) {
		this.core = core;
	}
	
	/**
	 * Create a SimpleToadletServer, using the settings from the SubConfig (the fproxy.*
	 * config).
	 */
	public SimpleToadletServer(SubConfig fproxyConfig, BucketFactory bucketFactory, Executor executor) throws IOException, InvalidConfigValueException {

		this.executor = executor;
		
		int configItemOrder = 0;
		
		fproxyConfig.register("enabled", true, configItemOrder++, true, true, "SimpleToadletServer.enabled", "SimpleToadletServer.enabledLong",
				new FProxyEnabledCallback());
		
		boolean enabled = fproxyConfig.getBoolean("enabled");
		
		List themes = new ArrayList();
		try {
			URL url = getClass().getResource("staticfiles/themes/");
			URLConnection urlConnection = url.openConnection();
			if (url.getProtocol().equals("file")) {
				File themesDirectory = new File(URLDecoder.decode(url.getPath(), "ISO-8859-1").replaceAll("\\|", ":"));
				File[] themeDirectories = themesDirectory.listFiles();
				for (int themeIndex = 0; (themeDirectories != null) && (themeIndex < themeDirectories.length); themeIndex++) {
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
		fproxyConfig.register("ssl", false, configItemOrder++, true, true, "SimpleToadletServer.ssl", "SimpleToadletServer.sslLong",
				new FProxySSLCallback());
		fproxyConfig.register("port", DEFAULT_FPROXY_PORT, configItemOrder++, true, true, "SimpleToadletServer.port", "SimpleToadletServer.portLong",
				new FProxyPortCallback());
		fproxyConfig.register("bindTo", NetworkInterface.DEFAULT_BIND_TO, configItemOrder++, true, true, "SimpleToadletServer.bindTo", "SimpleToadletServer.bindToLong",
				new FProxyBindtoCallback());
		fproxyConfig.register("css", "clean", configItemOrder++, false, false, "SimpleToadletServer.cssName", "SimpleToadletServer.cssNameLong",
				new FProxyCSSNameCallback());
		fproxyConfig.register("CSSOverride", "", configItemOrder++, true, false, "SimpleToadletServer.cssOverride", "SimpleToadletServer.cssOverrideLong",
				new FProxyCSSOverrideCallback());
		fproxyConfig.register("advancedModeEnabled", false, configItemOrder++, true, false, "SimpleToadletServer.advancedMode", "SimpleToadletServer.advancedModeLong",
				new FProxyAdvancedModeEnabledCallback(this));
		fproxyConfig.register("javascriptEnabled", false, configItemOrder++, true, false, "SimpleToadletServer.enableJS", "SimpleToadletServer.enableJSLong",
				new FProxyJavascriptEnabledCallback(this));
		fproxyConfig.register("showPanicButton", false, configItemOrder++, true, true, "SimpleToadletServer.panicButton", "SimpleToadletServer.panicButtonLong",
				new BooleanCallback(){
				public boolean get(){
					return SimpleToadletServer.isPanicButtonToBeShown;
				}
			
				public void set(boolean value){
					if(value == SimpleToadletServer.isPanicButtonToBeShown) return;
					else	SimpleToadletServer.isPanicButtonToBeShown = value;
				}
		});
		
		// This is OFF BY DEFAULT because for example firefox has a limit of 2 persistent 
		// connections per server, but 8 non-persistent connections per server. We need 8 conns
		// more than we need the efficiency gain of reusing connections - especially on first
		// install.
		
		fproxyConfig.register("enablePersistentConnections", false, configItemOrder++, true, false, "SimpleToadletServer.enablePersistentConnections", "SimpleToadletServer.enablePersistentConnectionsLong",
				new BooleanCallback() {

					public boolean get() {
						synchronized(SimpleToadletServer.this) {
							return enablePersistentConnections;
						}
					}

					public void set(boolean val) throws InvalidConfigValueException {
						synchronized(SimpleToadletServer.this) {
							enablePersistentConnections = val;
						}
					}
		});
		enablePersistentConnections = fproxyConfig.getBoolean("enablePersistentConnections");
		
		// Off by default.
		// I had hoped it would yield a significant performance boost to bootstrap performance
		// on browsers with low numbers of simultaneous connections. Unfortunately the bottleneck
		// appears to be that the node does very few local requests compared to external requests
		// (for anonymity's sake).
		
		fproxyConfig.register("enableInlinePrefetch", false, configItemOrder++, true, false, "SimpleToadletServer.enableInlinePrefetch", "SimpleToadletServer.enableInlinePrefetchLong",
				new BooleanCallback() {

					public boolean get() {
						synchronized(SimpleToadletServer.this) {
							return enableInlinePrefetch;
						}
					}

					public void set(boolean val) throws InvalidConfigValueException {
						synchronized(SimpleToadletServer.this) {
							enableInlinePrefetch = val;
						}
					}
		});
		enableInlinePrefetch = fproxyConfig.getBoolean("enableInlinePrefetch");
		
		fproxyConfig.register("passthroughMaxSize", 2*1024*1024, configItemOrder++, true, false, "SimpleToadletServer.passthroughMaxSize", "SimpleToadletServer.passthroughMaxSizeLong", new FProxyPassthruMaxSize());
		FProxyToadlet.MAX_LENGTH = fproxyConfig.getInt("passthroughMaxSize");
		
		fproxyConfig.register("allowedHosts", "127.0.0.1,0:0:0:0:0:0:0:1", configItemOrder++, true, true, "SimpleToadletServer.allowedHosts", "SimpleToadletServer.allowedHostsLong",
				new FProxyAllowedHostsCallback());
		fproxyConfig.register("allowedHostsFullAccess", "127.0.0.1,0:0:0:0:0:0:0:1", configItemOrder++, true, true, "SimpleToadletServer.allowedFullAccess", 
				"SimpleToadletServer.allowedFullAccessLong",
				new StringCallback() {

					public String get() {
						return allowedFullAccess.getAllowedHosts();
					}

					public void set(String val) throws InvalidConfigValueException {
						allowedFullAccess.setAllowedHosts(val);
					}
			
		});
		allowedFullAccess = new AllowedHosts(fproxyConfig.getString("allowedHostsFullAccess"));
		fproxyConfig.register("doRobots", false, configItemOrder++, true, false, "SimpleToadletServer.doRobots", "SimpleToadletServer.doRobotsLong",
				new BooleanCallback() {
					public boolean get() {
						return doRobots;
					}
					public void set(boolean val) throws InvalidConfigValueException {
						doRobots = val;
					}
		});
		doRobots = fproxyConfig.getBoolean("doRobots");
		
		SimpleToadletServer.isPanicButtonToBeShown = fproxyConfig.getBoolean("showPanicButton");
		this.bf = bucketFactory;
		port = fproxyConfig.getInt("port");
		bindTo = fproxyConfig.getString("bindTo");
		cssName = fproxyConfig.getString("css");
		if((cssName.indexOf(':') != -1) || (cssName.indexOf('/') != -1))
			throw new InvalidConfigValueException("CSS name must not contain slashes or colons!");
		pageMaker = new PageMaker(cssName);
	
		if(!fproxyConfig.getOption("CSSOverride").isDefault()) {
			cssOverride = new File(fproxyConfig.getString("CSSOverride"));			
			pageMaker.setOverride(cssOverride);
		} else
			cssOverride = null;
		
		this.advancedModeEnabled = fproxyConfig.getBoolean("advancedModeEnabled");		
		toadlets = new LinkedList();

		if(SSL.available()) {
			ssl = fproxyConfig.getBoolean("ssl");
		}
		
		this.allowedHosts=fproxyConfig.getString("allowedHosts");

		if(!enabled) {
			Logger.normal(SimpleToadletServer.this, "Not starting FProxy as it's disabled");
			System.out.println("Not starting FProxy as it's disabled");
		} else {
			maybeGetNetworkInterface();
			myThread = new Thread(this, "SimpleToadletServer");
			myThread.setDaemon(true);
		}
		
		// Register static toadlet and startup toadlet
		
		StaticToadlet statictoadlet = new StaticToadlet();
		register(statictoadlet, "/static/", false, false);
		
		startupToadlet = new StartupToadlet(statictoadlet);
		register(startupToadlet, "/", false, false);
		
	}
	
	public StartupToadlet startupToadlet;
	
	public void removeStartupToadlet() {
		unregister(startupToadlet);
		// Ready to be GCed
		startupToadlet = null;
		// Not in the navbar.
	}
	
	private void maybeGetNetworkInterface() throws IOException {
		if (this.networkInterface!=null) return;
		if(ssl) {
			this.networkInterface = SSLNetworkInterface.create(port, this.bindTo, allowedHosts, executor, true);
		} else {
			this.networkInterface = NetworkInterface.create(port, this.bindTo, allowedHosts, executor, true);
		}
	}		

	public boolean doRobots() {
		return doRobots;
	}
	
	public void start() {
		if(myThread != null) try {
			maybeGetNetworkInterface();
			myThread.start();
			Logger.normal(this, "Starting FProxy on "+bindTo+ ':' +port);
			System.out.println("Starting FProxy on "+bindTo+ ':' +port);
		} catch (IOException e) {
			Logger.error(this, "Could not bind network port for FProxy?", e);
		}
	}
	
	public void register(Toadlet t, String urlPrefix, boolean atFront, boolean fullOnly) {
		register(t, urlPrefix, atFront, null, null, fullOnly, null);
	}
	
	public void register(Toadlet t, String urlPrefix, boolean atFront, String name, String title, boolean fullOnly, LinkEnabledCallback cb) {
		ToadletElement te = new ToadletElement(t, urlPrefix);
		if(atFront) toadlets.addFirst(te);
		else toadlets.addLast(te);
		t.container = this;
		if (name != null) {
			pageMaker.addNavigationLink(urlPrefix, name, title, fullOnly, cb);
		}
	}

	public synchronized void unregister(Toadlet t) {
		for(Iterator i=toadlets.iterator();i.hasNext();) {
			ToadletElement e = (ToadletElement) i.next();
			if(e.t == t) {
				i.remove();
				return;
			}
		}
	}
	
	public StartupToadlet getStartupToadlet() {
		return startupToadlet;
	}
	
	public Toadlet findToadlet(URI uri) throws PermanentRedirectException {
		Iterator i = toadlets.iterator();
		String path = uri.getPath();
		while(i.hasNext()) {
			ToadletElement te = (ToadletElement) i.next();
			
			if(path.startsWith(te.prefix))
				return te.t;
			if(te.prefix.length() > 0 && te.prefix.charAt(te.prefix.length()-1) == '/') {
				if(path.equals(te.prefix.substring(0, te.prefix.length()-1))) {
					URI newURI;
					try {
						newURI = new URI(te.prefix);
					} catch (URISyntaxException e) {
						throw new Error(e);
					}
					throw new PermanentRedirectException(newURI);
				}
			}
		}
		return null;
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
				if(Logger.shouldLog(Logger.MINOR, this))
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
			executor.execute(this, "HTTP socket handler@"+hashCode());
		}
		
		public void run() {
		    freenet.support.Logger.OSThread.logPID(this);
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Handling connection");
			try {
				ToadletContextImpl.handle(sock, SimpleToadletServer.this, pageMaker);
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("SimpleToadletServer request above failed.");
				Logger.error(this, "OOM in SocketHandler");
			} catch (Throwable t) {
				System.err.println("Caught in SimpleToadletServer: "+t);
				t.printStackTrace();
				Logger.error(this, "Caught in SimpleToadletServer: "+t, t);
			}
			if(logMINOR) Logger.minor(this, "Handled connection");
		}

	}

	public String getCSSName() {
		return this.cssName;
	}

	public void setCSSName(String name) {
		this.cssName = name;
	}

	public synchronized boolean isAdvancedModeEnabled() {
		return this.advancedModeEnabled;
	}
	
	public synchronized void enableAdvancedMode(boolean b){
		advancedModeEnabled = b;
	}

	public synchronized boolean isFProxyJavascriptEnabled() {
		return this.fProxyJavascriptEnabled;
	}
	
	public synchronized void enableFProxyJavascript(boolean b){
		fProxyJavascriptEnabled = b;
	}

	public String getFormPassword() {
		if(core == null) return "";
		return core.formPassword;
	}

	public boolean isAllowedFullAccess(InetAddress remoteAddr) {
		return this.allowedFullAccess.allowed(remoteAddr);
	}
	
	private static String l10n(String key, String pattern, String value) {
		return L10n.getString("SimpleToadletServer."+key, pattern, value);
	}

	private static String l10n(String key) {
		return L10n.getString("SimpleToadletServer."+key);
	}

	public HTMLNode addFormChild(HTMLNode parentNode, String target, String id) {
		HTMLNode formNode =
			parentNode.addChild("form", new String[] { "action", "method", "enctype", "id",  "accept-charset" }, 
					new String[] { target, "post", "multipart/form-data", id, "utf-8"} ).addChild("div");
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
				new String[] { "hidden", "formPassword", getFormPassword() });
		
		return formNode;
	}

	public void setBucketFactory(BucketFactory tempBucketFactory) {
		this.bf = tempBucketFactory;
	}

	public boolean isEnabled() {
		return myThread != null;
	}

	public BookmarkManager getBookmarks() {
		return bookmarkManager;
	}

	public FreenetURI[] getBookmarkURIs() {
		if(bookmarkManager == null) return new FreenetURI[0];
		return bookmarkManager.getBookmarkURIs();
	}

	public boolean enablePersistentConnections() {
		return enablePersistentConnections;
	}

	public boolean enableInlinePrefetch() {
		return enableInlinePrefetch;
	}

	public synchronized boolean allowPosts() {
		return !(bf instanceof ArrayBucketFactory);
	}

	public synchronized BucketFactory getBucketFactory() {
		return bf;
	}
	
}
