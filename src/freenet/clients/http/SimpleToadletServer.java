/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import org.tanukisoftware.wrapper.WrapperManager;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;

import freenet.client.filter.HTMLFilter;
import freenet.client.filter.LinkFilterExceptionProvider;
import freenet.clients.http.FProxyFetchInProgress.REFILTER_POLICY;
import freenet.clients.http.PageMaker.THEME;
import freenet.clients.http.bookmark.BookmarkManager;
import freenet.clients.http.updateableelements.PushDataManager;
import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.SubConfig;
import freenet.crypt.SSL;
import freenet.io.AllowedHosts;
import freenet.io.NetworkInterface;
import freenet.io.SSLNetworkInterface;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.PrioRunnable;
import freenet.node.SecurityLevelListener;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.node.useralerts.UserAlertManager;
import freenet.pluginmanager.FredPluginL10n;
import freenet.support.Executor;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.Ticker;
import freenet.support.api.BooleanCallback;
import freenet.support.api.BucketFactory;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.NativeThread;

/** 
 * The Toadlet (HTTP) Server
 * 
 * Provide a HTTP server for FProxy
 */
public final class SimpleToadletServer implements ToadletContainer, Runnable, LinkFilterExceptionProvider {
	/** List of urlPrefix / Toadlet */ 
	private final LinkedList<ToadletElement> toadlets;
	private static class ToadletElement {
		public ToadletElement(Toadlet t2, String urlPrefix, String menu, String name) {
			t = t2;
			prefix = urlPrefix;
			this.menu = menu;
			this.name = name;
		}
		Toadlet t;
		String prefix;
		String menu;
		String name;
	}

	// Socket / Binding
	private final int port;
	private String bindTo;
	private String allowedHosts;
	private NetworkInterface networkInterface;
	private boolean ssl = false;
	public static final int DEFAULT_FPROXY_PORT = 8888;
	
	// ACL
	private final AllowedHosts allowedFullAccess;
	private boolean publicGatewayMode;
	private final boolean wasPublicGatewayMode;
	
	// Theme 
	private THEME cssTheme;
	private File cssOverride;
	private boolean sendAllThemes;
	private boolean advancedModeEnabled;
	private final PageMaker pageMaker;
	private boolean fetchKeyBoxAboveBookmarks;
	
	// Control
	private Thread myThread;
	private final Executor executor;
	private final Random random;
	private BucketFactory bf;
	private volatile NodeClientCore core;
	
	// HTTP Option
	private boolean doRobots;
	private boolean enablePersistentConnections;
	private boolean enableInlinePrefetch;
	private boolean enableActivelinks;
	private boolean enableExtendedMethodHandling;
	private boolean enableCachingForChkAndSskKeys;
	
	// Something does not really belongs to here
	volatile static boolean isPanicButtonToBeShown;				// move to QueueToadlet ?
	volatile static boolean noConfirmPanic;
	public BookmarkManager bookmarkManager;				// move to WelcomeToadlet / BookmarkEditorToadlet ?
	private volatile boolean fProxyJavascriptEnabled;	// ugh?
	private volatile boolean fProxyWebPushingEnabled;	// ugh?
	private volatile boolean fproxyHasCompletedWizard;	// hmmm..
	private volatile boolean disableProgressPage;
	private int maxFproxyConnections;
	
	private int fproxyConnections;
	
	private boolean finishedStartup;
	
	/** The PushDataManager handles all the pushing tasks*/
	public PushDataManager pushDataManager; 
	
	/** The IntervalPusherManager handles interval pushing*/
	public IntervalPusherManager intervalPushManager;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	// Config Callbacks
	private class FProxySSLCallback extends BooleanCallback  {
		@Override
		public Boolean get() {
			return ssl;
		}
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			if(!SSL.available()) {
				throw new InvalidConfigValueException("Enable SSL support before use ssl with Fproxy");
			}
			ssl = val;
			throw new InvalidConfigValueException("Cannot change SSL on the fly, please restart freenet");
		}
		@Override
		public boolean isReadOnly() {
			return true;
		}
	}
	
	private static class FProxyPassthruMaxSizeNoProgress extends LongCallback {
		@Override
		public Long get() {
			return FProxyToadlet.MAX_LENGTH_NO_PROGRESS;
		}
		
		@Override
		public void set(Long val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			FProxyToadlet.MAX_LENGTH_NO_PROGRESS = val;
		}
	}

	private static class FProxyPassthruMaxSizeProgress extends LongCallback {
		@Override
		public Long get() {
			return FProxyToadlet.MAX_LENGTH_WITH_PROGRESS;
		}
		
		@Override
		public void set(Long val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			FProxyToadlet.MAX_LENGTH_WITH_PROGRESS = val;
		}
	}

	private class FProxyPortCallback extends IntCallback  {
		@Override
		public Integer get() {
			return port;
		}
		
		@Override
		public void set(Integer newPort) throws NodeNeedRestartException {
			if(port != newPort) {
				throw new NodeNeedRestartException("Port cannot change on the fly");
			}
		}
	}
	
	private class FProxyBindtoCallback extends StringCallback  {
		@Override
		public String get() {
			return bindTo;
		}
		
		@Override
		public void set(String bindTo) throws InvalidConfigValueException {
			String oldValue = get();
			if(!bindTo.equals(oldValue)) {
				String[] failedAddresses = networkInterface.setBindTo(bindTo, false);
				if(failedAddresses == null) {
					SimpleToadletServer.this.bindTo = bindTo;
				} else {
					// This is an advanced option for reasons of reducing clutter,
					// but it is expected to be used by regular users, not devs.
					// So we translate the error messages.
					networkInterface.setBindTo(oldValue, false);
					throw new InvalidConfigValueException(l10n("couldNotChangeBindTo", "failedInterfaces", Arrays.toString(failedAddresses)));
				}
			}
		}
	}
	private class FProxyAllowedHostsCallback extends StringCallback  {
		@Override
		public String get() {
			return networkInterface.getAllowedHosts();
		}
		
		@Override
		public void set(String allowedHosts) throws InvalidConfigValueException {
			if (!allowedHosts.equals(get())) {
				try {
				networkInterface.setAllowedHosts(allowedHosts);
				} catch(IllegalArgumentException e) {
					throw new InvalidConfigValueException(e);
				}
			}
		}
	}
	private class FProxyCSSNameCallback extends StringCallback implements EnumerableOptionCallback {
		@Override
		public String get() {
			return cssTheme.code;
		}
		
		@Override
		public void set(String CSSName) throws InvalidConfigValueException {
			if((CSSName.indexOf(':') != -1) || (CSSName.indexOf('/') != -1))
				throw new InvalidConfigValueException(l10n("illegalCSSName"));
			cssTheme = THEME.themeFromName(CSSName);
			pageMaker.setTheme(cssTheme);
			NodeClientCore core = SimpleToadletServer.this.core;
			if (core.node.pluginManager != null)
				core.node.pluginManager.setFProxyTheme(cssTheme);
			fetchKeyBoxAboveBookmarks = cssTheme.fetchKeyBoxAboveBookmarks;
		}

		@Override
		public String[] getPossibleValues() {
			return THEME.possibleValues;
		}
	}
	private class FProxyCSSOverrideCallback extends StringCallback  {
		@Override
		public String get() {
			return (cssOverride == null ? "" : cssOverride.toString());
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			NodeClientCore core = SimpleToadletServer.this.core;
			if(core == null) return;
			if(val.equals(get()) || val.equals(""))
				cssOverride = null;
			else {
				File tmp = new File(val.trim());
				if(!core.allowUploadFrom(tmp))
					throw new InvalidConfigValueException(l10n("cssOverrideNotInUploads", "filename", tmp.toString()));
				else if(!tmp.canRead() || !tmp.isFile())
					throw new InvalidConfigValueException(l10n("cssOverrideCantRead", "filename", tmp.toString()));
				File parent = tmp.getParentFile();
				// Basic sanity check.
				// Prevents user from specifying root dir.
				// They can still shoot themselves in the foot, but only when developing themes/using custom themes.
				// Because of the .. check above, any malicious thing cannot break out of the dir anyway.
				if(parent.getParentFile() == null)
					throw new InvalidConfigValueException(l10n("cssOverrideCantUseRootDir", "filename", parent.toString()));
				cssOverride = tmp;
			}
			if(cssOverride == null)
				pageMaker.setOverride(null);
			else {
				pageMaker.setOverride(StaticToadlet.OVERRIDE_URL + cssOverride.getName());
			}
			
		}
	}
	private class FProxyEnabledCallback extends BooleanCallback  {
		@Override
		public Boolean get() {
			synchronized(SimpleToadletServer.this) {
				return myThread != null;
			}
		}
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			synchronized(SimpleToadletServer.this) {
				if(val) {
					// Start it
					myThread = new Thread(SimpleToadletServer.this, "SimpleToadletServer");
				} else {
					myThread.interrupt();
					myThread = null;
					SimpleToadletServer.this.notifyAll();
					return;
				}
			}
			createFproxy();
			myThread.setDaemon(true);
			myThread.start();
		}
	}
	private static class FProxyAdvancedModeEnabledCallback extends BooleanCallback  {
		private final SimpleToadletServer ts;
		
		FProxyAdvancedModeEnabledCallback(SimpleToadletServer ts){
			this.ts = ts;
		}
		
		@Override
		public Boolean get() {
			return ts.isAdvancedModeEnabled();
		}
		
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			ts.setAdvancedMode(val);
		}
	}
	private static class FProxyJavascriptEnabledCallback extends BooleanCallback  {
		
		private final SimpleToadletServer ts;
		
		FProxyJavascriptEnabledCallback(SimpleToadletServer ts){
			this.ts = ts;
		}
		
		@Override
		public Boolean get() {
			return ts.isFProxyJavascriptEnabled();
		}
		
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
				ts.enableFProxyJavascript(val);
		}
	}
	
	private static class FProxyWebPushingEnabledCallback extends BooleanCallback{
		
		private final SimpleToadletServer ts;
		
		FProxyWebPushingEnabledCallback(SimpleToadletServer ts){
			this.ts=ts;
		}
		
		@Override
		public Boolean get() {
			return ts.isFProxyWebPushingEnabled();
		}
		
		@Override
		public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
			if (get().equals(val))
				return;
				ts.enableFProxyWebPushing(val);
		}
	}
	
	private boolean haveCalledFProxy = false;
	
	// FIXME factor this out to a global helper class somehow?
	
	private class ReFilterCallback extends StringCallback implements EnumerableOptionCallback {

		@Override
		public String[] getPossibleValues() {
			REFILTER_POLICY[] possible = REFILTER_POLICY.values();
			String[] ret = new String[possible.length];
			for(int i=0;i<possible.length;i++)
				ret[i] = possible[i].name();
			return ret;
		}

		@Override
		public String get() {
			return refilterPolicy.name();
		}

		@Override
		public void set(String val) throws InvalidConfigValueException,
				NodeNeedRestartException {
			refilterPolicy = REFILTER_POLICY.valueOf(val);
		}
		
	};

	public void createFproxy() {
		NodeClientCore core = this.core;
		Node node = core.node;
		synchronized(this) {
			if(haveCalledFProxy) return;
			haveCalledFProxy = true;
		}
		
		pushDataManager=new PushDataManager(getTicker());
		intervalPushManager=new IntervalPusherManager(getTicker(), pushDataManager);
		bookmarkManager = new BookmarkManager(core, publicGatewayMode());
		try {
			FProxyToadlet.maybeCreateFProxyEtc(core, node, node.config, this);
		} catch (IOException e) {
			Logger.error(this, "Could not start fproxy: "+e, e);
			System.err.println("Could not start fproxy:");
			e.printStackTrace();
		}
	}
	

	
	public void setCore(NodeClientCore core) {
		this.core = core;
	}
	
	/**
	 * Create a SimpleToadletServer, using the settings from the SubConfig (the fproxy.*
	 * config).
	 */
	public SimpleToadletServer(SubConfig fproxyConfig, BucketFactory bucketFactory, Executor executor, Node node) throws IOException, InvalidConfigValueException {

		this.executor = executor;
		this.core = null; // setCore() will be called later. 
		this.random = new Random();
		
		int configItemOrder = 0;
		
		fproxyConfig.register("enabled", true, configItemOrder++, true, true, "SimpleToadletServer.enabled", "SimpleToadletServer.enabledLong",
				new FProxyEnabledCallback());
		
		boolean enabled = fproxyConfig.getBoolean("enabled");
		
		fproxyConfig.register("ssl", false, configItemOrder++, true, true, "SimpleToadletServer.ssl", "SimpleToadletServer.sslLong",
				new FProxySSLCallback());
		fproxyConfig.register("port", DEFAULT_FPROXY_PORT, configItemOrder++, true, true, "SimpleToadletServer.port", "SimpleToadletServer.portLong",
				new FProxyPortCallback(), false);
		fproxyConfig.register("bindTo", NetworkInterface.DEFAULT_BIND_TO, configItemOrder++, true, true, "SimpleToadletServer.bindTo", "SimpleToadletServer.bindToLong",
				new FProxyBindtoCallback());
		fproxyConfig.register("css", PageMaker.THEME.getDefault().code, configItemOrder++, false, false, "SimpleToadletServer.cssName", "SimpleToadletServer.cssNameLong",
				new FProxyCSSNameCallback());
		fproxyConfig.register("CSSOverride", "", configItemOrder++, true, false, "SimpleToadletServer.cssOverride", "SimpleToadletServer.cssOverrideLong",
				new FProxyCSSOverrideCallback());
		fproxyConfig.register("sendAllThemes", false, configItemOrder++, true, false, "SimpleToadletServer.sendAllThemes", "SimpleToadletServer.sendAllThemesLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return sendAllThemes;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						sendAllThemes = val;
					}
				});
		sendAllThemes = fproxyConfig.getBoolean("sendAllThemes");
		
		fproxyConfig.register("advancedModeEnabled", false, configItemOrder++, true, false, "SimpleToadletServer.advancedMode", "SimpleToadletServer.advancedModeLong",
				new FProxyAdvancedModeEnabledCallback(this));

		fproxyConfig.register("enableExtendedMethodHandling", false, configItemOrder++, true, false, "SimpleToadletServer.enableExtendedMethodHandling", "SimpleToadletServer.enableExtendedMethodHandlingLong",
				new BooleanCallback() {
					@Override
					public Boolean get() {
						return enableExtendedMethodHandling;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						if(get().equals(val)) return;
						enableExtendedMethodHandling = val;
					}
		});
		fproxyConfig.register("javascriptEnabled", true, configItemOrder++, true, false, "SimpleToadletServer.enableJS", "SimpleToadletServer.enableJSLong",
				new FProxyJavascriptEnabledCallback(this));
		fproxyConfig.register("webPushingEnabled", false, configItemOrder++, true, false, "SimpleToadletServer.enableWP", "SimpleToadletServer.enableWPLong", new FProxyWebPushingEnabledCallback(this));
		fproxyConfig.register("hasCompletedWizard", false, configItemOrder++, true, false, "SimpleToadletServer.hasCompletedWizard", "SimpleToadletServer.hasCompletedWizardLong",
				new BooleanCallback() {
					@Override
					public Boolean get() {
						return fproxyHasCompletedWizard;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						if(get().equals(val)) return;
						fproxyHasCompletedWizard = val;
					}
		});
		fproxyConfig.register("disableProgressPage", false, configItemOrder++, true, false, "SimpleToadletServer.disableProgressPage", "SimpleToadletServer.disableProgressPageLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return disableProgressPage;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						disableProgressPage = val;
					}
			
		});
		fproxyHasCompletedWizard = fproxyConfig.getBoolean("hasCompletedWizard");
		fProxyJavascriptEnabled = fproxyConfig.getBoolean("javascriptEnabled");
		fProxyWebPushingEnabled = fproxyConfig.getBoolean("webPushingEnabled");
		disableProgressPage = fproxyConfig.getBoolean("disableProgressPage");
		enableExtendedMethodHandling = fproxyConfig.getBoolean("enableExtendedMethodHandling");

		fproxyConfig.register("showPanicButton", false, configItemOrder++, true, true, "SimpleToadletServer.panicButton", "SimpleToadletServer.panicButtonLong",
				new BooleanCallback(){
				@Override
				public Boolean get() {
					return SimpleToadletServer.isPanicButtonToBeShown;
				}
			
				@Override
				public void set(Boolean value) {
					if(value == SimpleToadletServer.isPanicButtonToBeShown) return;
					else	SimpleToadletServer.isPanicButtonToBeShown = value;
				}
		});
		
		fproxyConfig.register("noConfirmPanic", false, configItemOrder++, true, true, "SimpleToadletServer.noConfirmPanic", "SimpleToadletServer.noConfirmPanicLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return SimpleToadletServer.noConfirmPanic;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						if(val == SimpleToadletServer.noConfirmPanic) return;
						else SimpleToadletServer.noConfirmPanic = val;
					}
		});
		
		fproxyConfig.register("publicGatewayMode", false, configItemOrder++, true, true, "SimpleToadletServer.publicGatewayMode", "SimpleToadletServer.publicGatewayModeLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return publicGatewayMode;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				if(publicGatewayMode == val) return;
				publicGatewayMode = val;
				throw new NodeNeedRestartException(l10n("publicGatewayModeNeedsRestart"));
			}
			
		});
		wasPublicGatewayMode = publicGatewayMode = fproxyConfig.getBoolean("publicGatewayMode");
		
		// This is OFF BY DEFAULT because for example firefox has a limit of 2 persistent 
		// connections per server, but 8 non-persistent connections per server. We need 8 conns
		// more than we need the efficiency gain of reusing connections - especially on first
		// install.
		
		fproxyConfig.register("enablePersistentConnections", false, configItemOrder++, true, false, "SimpleToadletServer.enablePersistentConnections", "SimpleToadletServer.enablePersistentConnectionsLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						synchronized(SimpleToadletServer.this) {
							return enablePersistentConnections;
						}
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
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

					@Override
					public Boolean get() {
						synchronized(SimpleToadletServer.this) {
							return enableInlinePrefetch;
						}
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						synchronized(SimpleToadletServer.this) {
							enableInlinePrefetch = val;
						}
					}
		});
		enableInlinePrefetch = fproxyConfig.getBoolean("enableInlinePrefetch");
		
		fproxyConfig.register("enableActivelinks", false, configItemOrder++, false, false, "SimpleToadletServer.enableActivelinks", "SimpleToadletServer.enableActivelinksLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return enableActivelinks;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				enableActivelinks = val;
			}
			
		});
		enableActivelinks = fproxyConfig.getBoolean("enableActivelinks");
		
		fproxyConfig.register("passthroughMaxSize", FProxyToadlet.MAX_LENGTH_NO_PROGRESS, configItemOrder++, true, false, "SimpleToadletServer.passthroughMaxSize", "SimpleToadletServer.passthroughMaxSizeLong", new FProxyPassthruMaxSizeNoProgress(), true);
		FProxyToadlet.MAX_LENGTH_NO_PROGRESS = fproxyConfig.getLong("passthroughMaxSize");
		fproxyConfig.register("passthroughMaxSizeProgress", FProxyToadlet.MAX_LENGTH_WITH_PROGRESS, configItemOrder++, true, false, "SimpleToadletServer.passthroughMaxSizeProgress", "SimpleToadletServer.passthroughMaxSizeProgressLong", new FProxyPassthruMaxSizeProgress(), true);
		FProxyToadlet.MAX_LENGTH_WITH_PROGRESS = fproxyConfig.getLong("passthroughMaxSizeProgress");
		System.out.println("Set fproxy max length to "+FProxyToadlet.MAX_LENGTH_NO_PROGRESS+" and max length with progress to "+FProxyToadlet.MAX_LENGTH_WITH_PROGRESS+" = "+fproxyConfig.getLong("passthroughMaxSizeProgress"));

		fproxyConfig.register("enableCachingForChkAndSskKeys", false, configItemOrder++, true, true, "SimpleToadletServer.enableCachingForChkAndSskKeys", "SimpleToadletServer.enableCachingForChkAndSskKeysLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return enableCachingForChkAndSskKeys;
			}

			@Override
			public void set(Boolean value) throws InvalidConfigValueException, NodeNeedRestartException {
				enableCachingForChkAndSskKeys = value;
			}
		});
		enableCachingForChkAndSskKeys = fproxyConfig.getBoolean("enableCachingForChkAndSskKeys");
		fproxyConfig.register("allowedHosts", "127.0.0.1,0:0:0:0:0:0:0:1", configItemOrder++, true, true, "SimpleToadletServer.allowedHosts", "SimpleToadletServer.allowedHostsLong",
				new FProxyAllowedHostsCallback());
		fproxyConfig.register("allowedHostsFullAccess", "127.0.0.1,0:0:0:0:0:0:0:1", configItemOrder++, true, true, "SimpleToadletServer.allowedFullAccess", 
				"SimpleToadletServer.allowedFullAccessLong",
				new StringCallback() {

					@Override
					public String get() {
						return allowedFullAccess.getAllowedHosts();
					}

					@Override
					public void set(String val) throws InvalidConfigValueException {
						try {
						allowedFullAccess.setAllowedHosts(val);
						} catch(IllegalArgumentException e) {
							throw new InvalidConfigValueException(e);
						}
					}
			
		});
		allowedFullAccess = new AllowedHosts(fproxyConfig.getString("allowedHostsFullAccess"));
		fproxyConfig.register("doRobots", false, configItemOrder++, true, false, "SimpleToadletServer.doRobots", "SimpleToadletServer.doRobotsLong",
				new BooleanCallback() {
					@Override
					public Boolean get() {
						return doRobots;
					}
					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						doRobots = val;
					}
		});
		doRobots = fproxyConfig.getBoolean("doRobots");
		
		// We may not know what the overall thread limit is yet so just set it to 100.
		fproxyConfig.register("maxFproxyConnections", 100, configItemOrder++, true, false, "SimpleToadletServer.maxFproxyConnections", "SimpleToadletServer.maxFproxyConnectionsLong",
				new IntCallback() {

					@Override
					public Integer get() {
						synchronized(SimpleToadletServer.this) {
							return maxFproxyConnections;
						}
					}

					@Override
					public void set(Integer val) {
						synchronized(SimpleToadletServer.this) {
							maxFproxyConnections = val;
							SimpleToadletServer.this.notifyAll();
						}
					}
			
		}, false);
		maxFproxyConnections = fproxyConfig.getInt("maxFproxyConnections");
		
		fproxyConfig.register("metaRefreshSamePageInterval", 1, configItemOrder++, true, false, "SimpleToadletServer.metaRefreshSamePageInterval", "SimpleToadletServer.metaRefreshSamePageIntervalLong",
				new IntCallback() {

					@Override
					public Integer get() {
						return HTMLFilter.metaRefreshSamePageMinInterval;
					}

					@Override
					public void set(Integer val)
							throws InvalidConfigValueException,
							NodeNeedRestartException {
						if(val < -1) throw new InvalidConfigValueException("-1 = disabled, 0+ = set a minimum interval"); // FIXME l10n
						HTMLFilter.metaRefreshSamePageMinInterval = val;
					}
		}, false);
		HTMLFilter.metaRefreshSamePageMinInterval = Math.max(-1, fproxyConfig.getInt("metaRefreshSamePageInterval"));
		
		fproxyConfig.register("metaRefreshRedirectInterval", 1, configItemOrder++, true, false, "SimpleToadletServer.metaRefreshRedirectInterval", "SimpleToadletServer.metaRefreshRedirectIntervalLong",
				new IntCallback() {

					@Override
					public Integer get() {
						return HTMLFilter.metaRefreshRedirectMinInterval;
					}

					@Override
					public void set(Integer val)
							throws InvalidConfigValueException,
							NodeNeedRestartException {
						if(val < -1) throw new InvalidConfigValueException("-1 = disabled, 0+ = set a minimum interval"); // FIXME l10n
						HTMLFilter.metaRefreshRedirectMinInterval = val;
					}
		}, false);
		HTMLFilter.metaRefreshRedirectMinInterval = Math.max(-1, fproxyConfig.getInt("metaRefreshRedirectInterval"));

		fproxyConfig.register("embedM3uPlayerInFreesites", true, configItemOrder++, true, false, "SimpleToadletServer.embedM3uPlayerInFreesites", "SimpleToadletServer.embedM3uPlayerInFreesitesLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return HTMLFilter.embedM3uPlayer;
					}

					@Override
					public void set(Boolean val) {
						HTMLFilter.embedM3uPlayer = val;
					}
				});
		HTMLFilter.embedM3uPlayer = fproxyConfig.getBoolean("embedM3uPlayerInFreesites");

		fproxyConfig.register("refilterPolicy", "RE_FILTER",
				configItemOrder++, true, false, "SimpleToadletServer.refilterPolicy", "SimpleToadletServer.refilterPolicyLong", new ReFilterCallback());
		
		this.refilterPolicy = REFILTER_POLICY.valueOf(fproxyConfig.getString("refilterPolicy"));
		
		// Network seclevel not physical seclevel because bad filtering can cause network level anonymity breaches.
		SimpleToadletServer.isPanicButtonToBeShown = fproxyConfig.getBoolean("showPanicButton");
		SimpleToadletServer.noConfirmPanic = fproxyConfig.getBoolean("noConfirmPanic");
		
		this.bf = bucketFactory;
		port = fproxyConfig.getInt("port");
		bindTo = fproxyConfig.getString("bindTo");
		String cssName = fproxyConfig.getString("css");
		if((cssName.indexOf(':') != -1) || (cssName.indexOf('/') != -1))
			throw new InvalidConfigValueException("CSS name must not contain slashes or colons!");
		cssTheme = THEME.themeFromName(cssName);
		pageMaker = new PageMaker(cssTheme, node);
	
		if(!fproxyConfig.getOption("CSSOverride").isDefault()) {
			cssOverride = new File(fproxyConfig.getString("CSSOverride"));
			pageMaker.setOverride(StaticToadlet.OVERRIDE_URL + cssOverride.getName());
		} else {
			cssOverride = null;
			pageMaker.setOverride(null);
		}

		fproxyConfig.register("fetchKeyBoxAboveBookmarks", cssTheme.fetchKeyBoxAboveBookmarks, configItemOrder++,
				false, false, "SimpleToadletServer.fetchKeyBoxAboveBookmarks",
				"SimpleToadletServer.fetchKeyBoxAboveBookmarksLong", new BooleanCallback() {
					@Override
					public Boolean get() {
						return fetchKeyBoxAboveBookmarks;
					}

					@Override
					public void set(Boolean val) {
						if(get().equals(val)) return;
						fetchKeyBoxAboveBookmarks = val;
					}
				});
		fetchKeyBoxAboveBookmarks = fproxyConfig.getBoolean("fetchKeyBoxAboveBookmarks");
		
		this.advancedModeEnabled = fproxyConfig.getBoolean("advancedModeEnabled");
		toadlets = new LinkedList<ToadletElement>();

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
		register(statictoadlet, null, "/static/", false, false);

		
		// "Freenet is starting up..." page, to be removed at #removeStartupToadlet()
		startupToadlet = new StartupToadlet(statictoadlet);
		register(startupToadlet, null, "/", false, false);
	}
	
	public StartupToadlet startupToadlet;
	
	public void removeStartupToadlet() {
		// setCore() must have been called first. It is in fact called much earlier on.
		synchronized(this) {
			unregister(startupToadlet);
			// Ready to be GCed
			startupToadlet = null;
			// Not in the navbar.
		}
	}
	
	private void maybeGetNetworkInterface() throws IOException {
		if (this.networkInterface!=null) return;
		if(ssl) {
			this.networkInterface = SSLNetworkInterface.create(port, this.bindTo, allowedHosts, executor, true);
		} else {
			this.networkInterface = NetworkInterface.create(port, this.bindTo, allowedHosts, executor, true);
		}
	}		

	@Override
	public boolean doRobots() {
		return doRobots;
	}
	
	@Override
	public boolean publicGatewayMode() {
		return wasPublicGatewayMode;
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
	
	public void finishStart() {
		core.node.securityLevels.addNetworkThreatLevelListener(new SecurityLevelListener<NETWORK_THREAT_LEVEL>() {

			@Override
			public void onChange(NETWORK_THREAT_LEVEL oldLevel,
					NETWORK_THREAT_LEVEL newLevel) {
				// At LOW, we do ACCEPT_OLD.
				// Otherwise we do RE_FILTER.
				// But we don't change it unless it changes from LOW to not LOW.
				if(newLevel == NETWORK_THREAT_LEVEL.LOW && newLevel != oldLevel) {
					refilterPolicy = REFILTER_POLICY.ACCEPT_OLD;
				} else if(oldLevel == NETWORK_THREAT_LEVEL.LOW && newLevel != oldLevel) {
					refilterPolicy = REFILTER_POLICY.RE_FILTER;
				}
			}
			
		});
		core.node.securityLevels.addPhysicalThreatLevelListener(new SecurityLevelListener<PHYSICAL_THREAT_LEVEL> () {

			@Override
			public void onChange(PHYSICAL_THREAT_LEVEL oldLevel, PHYSICAL_THREAT_LEVEL newLevel) {
				if(newLevel != oldLevel && newLevel == PHYSICAL_THREAT_LEVEL.LOW) {
					isPanicButtonToBeShown = false;
				} else if(newLevel != oldLevel) {
					isPanicButtonToBeShown = true;
				}
			}
			
		});
		synchronized(this) {
			finishedStartup = true;
		}
	}
	
	@Override
	public void register(Toadlet t, String menu, String urlPrefix, boolean atFront, boolean fullOnly) {
		register(t, menu, urlPrefix, atFront, null, null, fullOnly, null, null);
	}
	
	@Override
	public void register(Toadlet t, String menu, String urlPrefix, boolean atFront, String name, String title, boolean fullOnly, LinkEnabledCallback cb) {
		register(t, menu, urlPrefix, atFront, name, title, fullOnly, cb, null);
	}
	
	@Override
	public void register(Toadlet t, String menu, String urlPrefix, boolean atFront, String name, String title, boolean fullOnly, LinkEnabledCallback cb, FredPluginL10n l10n) {
		ToadletElement te = new ToadletElement(t, urlPrefix, menu, name);
		synchronized(toadlets) {
			if(atFront) toadlets.addFirst(te);
			else toadlets.addLast(te);
			t.container = this;
		}
		if (menu != null && name != null) {
			pageMaker.addNavigationLink(menu, urlPrefix, name, title, fullOnly, cb, l10n);
		}
	}
	
	public void registerMenu(String link, String name, String title, FredPluginL10n plugin) {
		pageMaker.addNavigationCategory(link, name, title, plugin);
	}

	@Override
	public void unregister(Toadlet t) {
		ToadletElement e = null;
		synchronized(toadlets) {
			for(Iterator<ToadletElement> i=toadlets.iterator();i.hasNext();) {
				e = i.next();
				if(e.t == t) {
					i.remove();
					break;
				}
			}
		}
		if(e != null && e.t == t) {
			if(e.menu != null && e.name != null) {
				pageMaker.removeNavigationLink(e.menu, e.name);
			}
		}
	}
	
	public StartupToadlet getStartupToadlet() {
		return startupToadlet;
	}
	
	@Override
	public boolean fproxyHasCompletedWizard() {
		return fproxyHasCompletedWizard;
	}
	
	@Override
	public Toadlet findToadlet(URI uri) throws PermanentRedirectException {
		String path = uri.getPath();

		// Show the wizard until dismissed by the user (See bug #2624)
		NodeClientCore core = this.core;
		if(core != null && core.node != null && !fproxyHasCompletedWizard) {
			//If the user has not completed the wizard, only allow access to the wizard and static
			//resources. Anything else redirects to the first page of the wizard.
			if (!(path.startsWith(FirstTimeWizardToadlet.TOADLET_URL) ||
				path.startsWith(StaticToadlet.ROOT_URL) ||
				path.startsWith(ExternalLinkToadlet.PATH) ||
				path.equals("/favicon.ico"))) {
				try {
					throw new PermanentRedirectException(new URI(null, null, null, -1, FirstTimeWizardToadlet.TOADLET_URL, uri.getQuery(), null));
				} catch(URISyntaxException e) { throw new Error(e); }
			}
		}

		synchronized(toadlets) {
			for(ToadletElement te: toadlets) {
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
		}
		return null;
	}

	@Override
	public void run() {
		boolean finishedStartup = false;
		while(true) {
			synchronized(this) {
				while(fproxyConnections > maxFproxyConnections) {
					try {
						wait();
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				if((!finishedStartup) && this.finishedStartup)
					finishedStartup = true;
				if(myThread == null) return;
			}
			Socket conn = networkInterface.accept();
			if (WrapperManager.hasShutdownHookBeenTriggered())
				return;
            if(conn == null)
                continue; // timeout
            if(logMINOR)
                Logger.minor(this, "Accepted connection");
            SocketHandler sh = new SocketHandler(conn, finishedStartup);
            sh.start();
		}
	}
	
	public class SocketHandler implements PrioRunnable {

		Socket sock;
		final boolean finishedStartup;
		
		public SocketHandler(Socket conn, boolean finishedStartup) {
			this.sock = conn;
			this.finishedStartup = finishedStartup;
		}

		void start() {
			if(finishedStartup)
				executor.execute(this, "HTTP socket handler@"+hashCode());
			else
				new Thread(this).start();
            synchronized(SimpleToadletServer.this) {
            	fproxyConnections++;
            }
		}
		
		@Override
		public void run() {
		    freenet.support.Logger.OSThread.logPID(this);
			if(logMINOR) Logger.minor(this, "Handling connection");
			try {
				ToadletContextImpl.handle(sock, SimpleToadletServer.this, pageMaker, getUserAlertManager(), bookmarkManager);
			} catch (Throwable t) {
				System.err.println("Caught in SimpleToadletServer: "+t);
				t.printStackTrace();
				Logger.error(this, "Caught in SimpleToadletServer: "+t, t);
			} finally {
	            synchronized(SimpleToadletServer.this) {
	            	fproxyConnections--;
	            	SimpleToadletServer.this.notifyAll();
	            }
			}
			if(logMINOR) Logger.minor(this, "Handled connection");
		}

		@Override
		public int getPriority() {
			return NativeThread.HIGH_PRIORITY-1;
		}

	}

	@Override
	public THEME getTheme() {
		return this.cssTheme;
	}

	public UserAlertManager getUserAlertManager() {
		NodeClientCore core = this.core;
		if(core == null) return null;
		return core.alerts;
	}

	public void setCSSName(THEME theme) {
		this.cssTheme = theme;
	}

	@Override
	public synchronized boolean sendAllThemes() {
		return this.sendAllThemes;
	}

	@Override
	public synchronized boolean isAdvancedModeEnabled() {
		return this.advancedModeEnabled;
	}
	
	@Override
	public void setAdvancedMode(boolean enabled) {
		synchronized(this) {
			if(advancedModeEnabled == enabled) return;
			advancedModeEnabled = enabled;
		}
		core.node.config.store();
	}

	@Override
	public synchronized boolean isFProxyJavascriptEnabled() {
		return this.fProxyJavascriptEnabled;
	}
	
	public synchronized void enableFProxyJavascript(boolean b){
		fProxyJavascriptEnabled = b;
	}
	
	@Override
	public synchronized boolean isFProxyWebPushingEnabled() {
		return this.fProxyWebPushingEnabled;
	}
	
	public synchronized void enableFProxyWebPushing(boolean b){
		fProxyWebPushingEnabled = b;
	}

	@Override
	public String getFormPassword() {
		if(core == null) return "";
		return core.formPassword;
	}

	@Override
	public boolean isAllowedFullAccess(InetAddress remoteAddr) {
		return this.allowedFullAccess.allowed(remoteAddr);
	}
	
	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("SimpleToadletServer."+key, pattern, value);
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("SimpleToadletServer."+key);
	}

	@Override
	public HTMLNode addFormChild(HTMLNode parentNode, String target, String id) {
		HTMLNode formNode =
			parentNode.addChild("div")
			.addChild("form", new String[] { "action", "method", "enctype", "id",  "accept-charset" }, 
					new String[] { target, "post", "multipart/form-data", id, "utf-8"} );
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

	@Override
	public boolean enablePersistentConnections() {
		return enablePersistentConnections;
	}

	@Override
	public boolean enableInlinePrefetch() {
		return enableInlinePrefetch;
	}

	@Override
	public boolean enableExtendedMethodHandling() {
		return enableExtendedMethodHandling;
	}

	@Override
	public boolean enableCachingForChkAndSskKeys() {
		return enableCachingForChkAndSskKeys;
	}

	@Override
	public synchronized boolean allowPosts() {
		return !(bf instanceof ArrayBucketFactory);
	}

	@Override
	public synchronized BucketFactory getBucketFactory() {
		return bf;
	}
	


	@Override
	public boolean enableActivelinks() {
		return enableActivelinks;
	}



	@Override
	public boolean disableProgressPage() {
		return disableProgressPage;
	}



	@Override
	public PageMaker getPageMaker() {
		return pageMaker;
	}
	
	public Ticker getTicker(){
		return core.node.getTicker();
	}
	
	public NodeClientCore getCore(){
		return core;
	}
	
	private REFILTER_POLICY refilterPolicy;

	@Override
	public REFILTER_POLICY getReFilterPolicy() {
		return refilterPolicy;
	}

	@Override
	public File getOverrideFile() {
		return cssOverride;
	}

	@Override
	public String getURL() {
		return getURL(null);
	}

	@Override
	public String getURL(String host) {
		StringBuffer sb = new StringBuffer();
		if(ssl)
			sb.append("https");
		else
			sb.append("http");
		sb.append("://");
		if(host == null)
			host = "127.0.0.1";
		sb.append(host);
		sb.append(":");
		sb.append(this.port);
		sb.append("/");
		return sb.toString();
	}

	@Override
	public boolean isSSL() {
		return ssl;
	}

	//
	// LINKFILTEREXCEPTIONPROVIDER METHODS
	//

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isLinkExcepted(URI link) {
		Toadlet toadlet = null;
		try {
			toadlet = findToadlet(link);
		} catch (PermanentRedirectException pre1) {
			/* ignore. */
		}
		if (toadlet instanceof LinkFilterExceptedToadlet) {
			return ((LinkFilterExceptedToadlet) toadlet).isLinkExcepted(link);
		}
		return false;
	}



	@Override
	public long generateUniqueID() {
		// FIXME increment a counter?
		return random.nextLong();
	}

}
