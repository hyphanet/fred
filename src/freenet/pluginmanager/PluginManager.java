/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.jar.Attributes;
import java.util.jar.JarException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipException;

import org.tanukisoftware.wrapper.WrapperManager;

import com.db4o.ObjectContainer;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.QueueToadlet;
import freenet.clients.http.PageMaker.THEME;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.SubConfig;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.l10n.BaseL10n;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.node.SecurityLevelListener;
import freenet.node.Ticker;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.fcp.ClientPut;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.JarClassLoader;
import freenet.support.Logger;
import freenet.support.SerialExecutor;
import freenet.support.api.BooleanCallback;
import freenet.support.api.HTTPRequest;
import freenet.support.api.StringArrCallback;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread;

public class PluginManager {

	/*
	 *
	 * TODO: Synchronize
	 * TODO: Synchronize
	 * TODO: Synchronize
	 * TODO: Synchronize
	 * TODO: Synchronize
	 *
	 */
	private final HashMap<String, FredPlugin> toadletList;

	/* All currently starting plugins. */
	private final Set<PluginProgress> startingPlugins = new HashSet<PluginProgress>();
	private final Vector<PluginInfoWrapper> pluginWrappers;
	private final HashMap<String, PluginLoadFailedUserAlert> pluginsFailedLoad;
	final Node node;
	private final NodeClientCore core;
	SubConfig pmconfig;
	private boolean logMINOR;
	private boolean logDEBUG;
	private final HighLevelSimpleClient client;

	private static PluginManager selfinstance = null;

	private THEME fproxyTheme;

	private final SerialExecutor executor;

	private boolean alwaysLoadOfficialPluginsFromCentralServer = false;

	static final short PRIO = RequestStarter.INTERACTIVE_PRIORITY_CLASS;

	public PluginManager(Node node, int lastVersion) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		// config

		toadletList = new HashMap<String, FredPlugin>();
		pluginWrappers = new Vector<PluginInfoWrapper>();
		pluginsFailedLoad = new HashMap<String, PluginLoadFailedUserAlert>();
		this.node = node;
		this.core = node.clientCore;

		if(logMINOR)
			Logger.minor(this, "Starting Plugin Manager");

		if(logDEBUG)
			Logger.debug(this, "Initialize Plugin Manager config");

		client = core.makeClient(PRIO, true);

		// callback executor
		executor = new SerialExecutor(NativeThread.NORM_PRIORITY);
		executor.start(node.executor, "PM callback executor");

		pmconfig = new SubConfig("pluginmanager", node.config);
//		pmconfig.register("configfile", "fplugins.ini", 9, true, true, "PluginConfig.configFile", "PluginConfig.configFileLong",
//				new StringCallback() {
//			public String get() {
//				return configFile;
//			}
//
//			public void set(String val) throws InvalidConfigValueException {
//				configFile = val;
//			}
//		});
//		configFile = pmconfig.getString("configfile");
//		pmconfig.register("installdir", "fplugins", 9, true, true, "PluginConfig.installDir", "PluginConfig.installDirLong",
//				new StringCallback() {
//			public String get() {
//				return installDir;
//				//return getConfigLoadString();
//			}
//
//			public void set(String val) throws InvalidConfigValueException {
//				installDir = val;
//				//if(storeDir.equals(new File(val))) return;
//				// FIXME
//				//throw new InvalidConfigValueException(NodeL10n.getBase().getString("PluginManager.cannotSetOnceLoaded"));
//			}
//		});
//		installDir = pmconfig.getString("installdir");

		// Start plugins in the config
		pmconfig.register("loadplugin", null, 0, true, false, "PluginManager.loadedOnStartup", "PluginManager.loadedOnStartupLong",
			new StringArrCallback() {

				@Override
				public String[] get() {
					return getConfigLoadString();
				}

				@Override
				public void set(String[] val) throws InvalidConfigValueException {
					//if(storeDir.equals(new File(val))) return;
					// FIXME
					throw new InvalidConfigValueException(NodeL10n.getBase().getString("PluginManager.cannotSetOnceLoaded"));
				}

			@Override
				public boolean isReadOnly() {
					return true;
				}
			});

		toStart = pmconfig.getStringArr("loadplugin");

		if(lastVersion < 1237 && contains(toStart, "XMLLibrarian") && !contains(toStart, "Library")) {
			String[] newToStart = new String[toStart.length+1];
			System.arraycopy(toStart, 0, newToStart, 0, toStart.length);
			newToStart[toStart.length] = "Library";
			toStart = newToStart;
			System.err.println("Loading Library plugin, replaces XMLLibrarian, when upgrading from pre-1237");
		}

		if(contains(toStart, "KeyExplorer")) {
			for(int i=0;i<toStart.length;i++) {
				if("KeyExplorer".equals(toStart[i]))
					toStart[i] = "KeyUtils";
			}
			System.err.println("KeyExplorer plugin renamed to KeyUtils");
		}

		pmconfig.register("alwaysLoadOfficialPluginsFromCentralServer", false, 0, false, false, "PluginManager.alwaysLoadPluginsFromHTTPS", "PluginManager.alwaysLoadPluginsFromCentralServerLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return alwaysLoadOfficialPluginsFromCentralServer;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				alwaysLoadOfficialPluginsFromCentralServer = val;
			}

		});

		alwaysLoadOfficialPluginsFromCentralServer = pmconfig.getBoolean("alwaysLoadOfficialPluginsFromCentralServer");

		node.securityLevels.addNetworkThreatLevelListener(new SecurityLevelListener<NETWORK_THREAT_LEVEL>() {

			public void onChange(NETWORK_THREAT_LEVEL oldLevel, NETWORK_THREAT_LEVEL newLevel) {
				if(newLevel == oldLevel) return;
				if(newLevel == NETWORK_THREAT_LEVEL.LOW)
					alwaysLoadOfficialPluginsFromCentralServer = true;
				else if(oldLevel == NETWORK_THREAT_LEVEL.LOW)
					alwaysLoadOfficialPluginsFromCentralServer = false;
			}

		});

		pmconfig.finishedInitialization();

		fproxyTheme = THEME.themeFromName(node.config.get("fproxy").getString("css"));
		selfinstance = this;
	}

	private boolean contains(String[] array, String string) {
		for(String s : array)
			if(string.equals(s)) return true;
		return false;
	}

	private boolean started;
	private boolean stopping;
	private String[] toStart;

	public void start(Config config) {
		if(toStart != null)
			for(String name : toStart)
				startPluginAuto(name, false);
		synchronized(pluginWrappers) {
			started = true;
			toStart = null;
		}
	}
	
	public void stop(int maxWaitTime) {
		// Stop loading plugins.
		ArrayList<PluginProgress> matches = new ArrayList<PluginProgress>();
		synchronized(this) {
			stopping = true;
			for(Iterator<PluginProgress> i = startingPlugins.iterator();i.hasNext();) {
				PluginProgress progress = i.next();
				if(matches == null) matches = new ArrayList<PluginProgress>();
				matches.add(progress);
				i.remove();
			}
		}
		if(matches == null) return;
		for(PluginProgress progress : matches) {
			progress.kill();
		}
		// Stop already loaded plugins.
		ArrayList<PluginInfoWrapper> wrappers;
		synchronized(pluginWrappers) {
			wrappers = new ArrayList<PluginInfoWrapper>(pluginWrappers);
		}
		for(PluginInfoWrapper pi : wrappers)
			pi.startShutdownPlugin(this, false);
		long now = System.currentTimeMillis();
		long deadline = now + maxWaitTime;
		while(true) {
			int delta = (int) (deadline - now);
			if(delta <= 0) {
				String list = pluginList(wrappers);
				Logger.error(this, "Plugins still shutting down at timeout:\n"+list);
				System.err.println("Plugins still shutting down at timeout:\n"+list);
			} else {
				for(Iterator<PluginInfoWrapper> it = wrappers.listIterator();it.hasNext();) {
					PluginInfoWrapper pi = it.next();
					System.out.println("Waiting for plugin to finish shutting down: "+pi.getFilename());
					if(pi.finishShutdownPlugin(this, delta, false)) {
						it.remove();
					}
				}
				if(wrappers.isEmpty()) {
					Logger.normal(this, "All plugins unloaded");
					System.out.println("All plugins unloaded");
					return;
				}
				String list = pluginList(wrappers);
				Logger.error(this, "Plugins still shutting down:\n"+list);
				System.err.println("Plugins still shutting down:\n"+list);
			}
		}
	}

	private static String pluginList(ArrayList<PluginInfoWrapper> wrappers) {
		StringBuffer sb = new StringBuffer();
		for(PluginInfoWrapper pi : wrappers) {
			sb.append(pi.getFilename());
			sb.append('\n');
		}
		return sb.toString();
	}

	private String[] getConfigLoadString() {
		Vector<String> v = new Vector<String>();

		synchronized(pluginWrappers) {
			if(!started) return toStart;
			for(PluginInfoWrapper pi : pluginWrappers) {
				v.add(pi.getFilename());
			}
			for(String s : pluginsFailedLoad.keySet()) {
				v.add(s);
			}
		}

		return v.toArray(new String[v.size()]);
	}

	/**
	 * Returns a set of all currently starting plugins.
	 *
	 * @return All currently starting plugins
	 */
	public Set<PluginProgress> getStartingPlugins() {
		synchronized(startingPlugins) {
			return new HashSet<PluginProgress>(startingPlugins);
		}
	}
	// try to guess around...
	public PluginInfoWrapper startPluginAuto(final String pluginname, boolean store) {

		OfficialPluginDescription desc;
		if((desc = isOfficialPlugin(pluginname)) != null) {
			return startPluginOfficial(pluginname, store, desc, false, false);
		}

		try {
			new FreenetURI(pluginname); // test for MalformedURLException
			return startPluginFreenet(pluginname, store);
		} catch(MalformedURLException e) {
			// not a freenet key
		}

		File[] roots = File.listRoots();
		for(File f : roots) {
			if(pluginname.startsWith(f.getName()) && new File(pluginname).exists()) {
				return startPluginFile(pluginname, store);
			}
		}

		return startPluginURL(pluginname, store);
	}

	public PluginInfoWrapper startPluginOfficial(final String pluginname, boolean store, boolean force, boolean forceHTTPS) {
		return startPluginOfficial(pluginname, store, officialPlugins.get(pluginname), force, forceHTTPS);
	}

	public PluginInfoWrapper startPluginOfficial(final String pluginname, boolean store, OfficialPluginDescription desc, boolean force, boolean forceHTTPS) {
		if((alwaysLoadOfficialPluginsFromCentralServer && !force)|| force && forceHTTPS) {
			return realStartPlugin(new PluginDownLoaderOfficialHTTPS(), pluginname, store);
		} else {
			return realStartPlugin(new PluginDownLoaderOfficialFreenet(client, node, false), pluginname, store);
		}
	}

	public PluginInfoWrapper startPluginFile(final String filename, boolean store) {
		return realStartPlugin(new PluginDownLoaderFile(), filename, store);
	}

	public PluginInfoWrapper startPluginURL(final String filename, boolean store) {
		return realStartPlugin(new PluginDownLoaderURL(), filename, store);
	}

	public PluginInfoWrapper startPluginFreenet(final String filename, boolean store) {
		return realStartPlugin(new PluginDownLoaderFreenet(client, node, false), filename, store);
	}

	private PluginInfoWrapper realStartPlugin(final PluginDownLoader<?> pdl, final String filename, final boolean store) {
		if(filename.trim().length() == 0)
			return null;
		final PluginProgress pluginProgress = new PluginProgress(filename, pdl);
		synchronized(startingPlugins) {
			startingPlugins.add(pluginProgress);
		}
		Logger.normal(this, "Loading plugin: " + filename);
		FredPlugin plug;
		PluginInfoWrapper pi = null;
		try {
			plug = loadPlugin(pdl, filename, pluginProgress);
			if (plug == null)
				return null; // Already loaded
			pluginProgress.setProgress(PluginProgress.PROGRESS_STATE.STARTING);
			pi = PluginHandler.startPlugin(PluginManager.this, filename, plug, new PluginRespirator(node, PluginManager.this, plug));
			synchronized (pluginWrappers) {
				pluginWrappers.add(pi);
				pluginsFailedLoad.remove(filename);
			}
			Logger.normal(this, "Plugin loaded: " + filename);
		} catch (PluginNotFoundException e) {
			Logger.normal(this, "Loading plugin failed (" + filename + ')', e);
			String message = e.getMessage();
			boolean stillTrying = false;
			if(pdl instanceof PluginDownLoaderOfficialFreenet) {
				PluginDownLoaderOfficialFreenet downloader = (PluginDownLoaderOfficialFreenet) pdl;
				if(!(downloader.fatalFailure() || downloader.desperate || twoCopiesInStartingPlugins(filename))) {
					// Retry forever...
					final PluginDownLoaderOfficialFreenet retry =
						new PluginDownLoaderOfficialFreenet(client, node, true);
					stillTrying = true;
					node.getTicker().queueTimedJob(new Runnable() {

						public void run() {
							realStartPlugin(retry, filename, store);
						}

					}, 0);
				}
			} else if(pdl instanceof PluginDownLoaderFreenet) {
				PluginDownLoaderFreenet downloader = (PluginDownLoaderFreenet) pdl;
				if(!(downloader.fatalFailure() || downloader.desperate || twoCopiesInStartingPlugins(filename))) {
					// Retry forever...
					final PluginDownLoaderFreenet retry =
						new PluginDownLoaderFreenet(client, node, true);
					stillTrying = true;
					node.getTicker().queueTimedJob(new Runnable() {

						public void run() {
							realStartPlugin(retry, filename, store);
						}

					}, 0);
				}
			}
			PluginLoadFailedUserAlert newAlert =
				new PluginLoadFailedUserAlert(filename,
						pdl instanceof PluginDownLoaderOfficialHTTPS || pdl instanceof PluginDownLoaderOfficialFreenet, pdl instanceof PluginDownLoaderOfficialFreenet, stillTrying, e);
			PluginLoadFailedUserAlert oldAlert = null;
			synchronized (pluginWrappers) {
				oldAlert = pluginsFailedLoad.put(filename, newAlert);
			}
			core.alerts.register(newAlert);
			core.alerts.unregister(oldAlert);
		} catch (UnsupportedClassVersionError e) {
			Logger.error(this, "Could not load plugin " + filename + " : " + e,
					e);
			System.err.println("Could not load plugin " + filename + " : " + e);
			e.printStackTrace();
			System.err.println("Plugin " + filename + " appears to require a later JVM");
			Logger.error(this, "Plugin " + filename + " appears to require a later JVM");
			PluginLoadFailedUserAlert newAlert =
				new PluginLoadFailedUserAlert(filename, pdl instanceof PluginDownLoaderOfficialHTTPS || pdl instanceof PluginDownLoaderOfficialFreenet, pdl instanceof PluginDownLoaderOfficialFreenet, false, l10n("pluginReqNewerJVMTitle", "name", filename));
			PluginLoadFailedUserAlert oldAlert = null;
			synchronized (pluginWrappers) {
				oldAlert = pluginsFailedLoad.put(filename, newAlert);
			}
			core.alerts.register(newAlert);
			core.alerts.unregister(oldAlert);
		} catch (Throwable e) {
			Logger.error(this, "Could not load plugin " + filename + " : " + e, e);
			System.err.println("Could not load plugin " + filename + " : " + e);
			e.printStackTrace();
			System.err.println("Plugin "+filename+" is broken, but we want to retry after next startup");
			Logger.error(this, "Plugin "+filename+" is broken, but we want to retry after next startup");
			PluginLoadFailedUserAlert newAlert =
				new PluginLoadFailedUserAlert(filename, pdl instanceof PluginDownLoaderOfficialHTTPS || pdl instanceof PluginDownLoaderOfficialFreenet, pdl instanceof PluginDownLoaderOfficialFreenet, false, e);
			PluginLoadFailedUserAlert oldAlert = null;
			synchronized (pluginWrappers) {
				oldAlert = pluginsFailedLoad.put(filename, newAlert);
			}
			core.alerts.register(newAlert);
			core.alerts.unregister(oldAlert);
		} finally {
			synchronized (startingPlugins) {
				startingPlugins.remove(pluginProgress);
			}
		}
		/* try not to destroy the config. */
		synchronized(this) {
			if (store)
				core.storeConfig();
		}
		if(pi != null)
			node.nodeUpdater.startPluginUpdater(filename);
		return pi;
	}

	private synchronized boolean twoCopiesInStartingPlugins(String filename) {
		int count = 0;
		for(PluginProgress progress : startingPlugins) {
			if(filename.equals(progress.name)) {
				count++;
				if(count == 2) return true;
			}
		}
		return false;
	}

	class PluginLoadFailedUserAlert extends AbstractUserAlert {

		final String filename;
		final String message;
		final boolean official;
		final boolean officialFromFreenet;
		final boolean stillTryingOverFreenet;

		public PluginLoadFailedUserAlert(String filename, boolean official, boolean officialFromFreenet, boolean stillTryingOverFreenet, String message) {
			this.filename = filename;
			this.official = official;
			this.message = message;
			this.officialFromFreenet = officialFromFreenet;
			this.stillTryingOverFreenet = stillTryingOverFreenet;
		}

		public PluginLoadFailedUserAlert(String filename, boolean official, boolean officialFromFreenet, boolean stillTryingOverFreenet, Throwable e) {
			this.filename = filename;
			this.official = official;
			this.stillTryingOverFreenet = stillTryingOverFreenet;
			String msg = e.getMessage();
			if(msg == null) msg = e.toString();
			this.message = msg;
			this.officialFromFreenet = officialFromFreenet;
		}

		public String dismissButtonText() {
			return l10n("deleteFailedPluginButton");
		}

		public void onDismiss() {
			synchronized(pluginWrappers) {
				pluginsFailedLoad.remove(filename);
			}
			node.executor.execute(new Runnable() {

				public void run() {
					cancelRunningLoads(filename, null);
				}

			});
		}

		public String anchor() {
			return "pluginfailed:"+filename;
		}

		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			HTMLNode p = div.addChild("p");
			p.addChild("#", l10n("pluginLoadingFailedWithMessage", new String[] { "name", "message" }, new String[] { filename, message }));
			if(stillTryingOverFreenet) {
				div.addChild("p", l10n("pluginLoadingFailedStillTryingOverFreenet"));
			}

			if(official) {
				p = div.addChild("p");
				if(officialFromFreenet)
					p.addChild("#", l10n("officialPluginLoadFailedSuggestTryAgainFreenet"));
				else
					p.addChild("#", l10n("officialPluginLoadFailedSuggestTryAgainHTTPS"));

				HTMLNode reloadForm = div.addChild("form", new String[] { "action", "method" }, new String[] { "/plugins/", "post" });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "plugin-name", filename });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "pluginSource", "https" });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-official", l10n("officialPluginLoadFailedTryAgain") });

				if(!stillTryingOverFreenet) {
					reloadForm = div.addChild("form", new String[] { "action", "method" }, new String[] { "/plugins/", "post" });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "plugin-name", filename });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "pluginSource", "freenet" });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-official", l10n("officialPluginLoadFailedTryAgainFreenet") });
				}
			}

			return div;
		}

		public short getPriorityClass() {
			return UserAlert.ERROR;
		}

		public String getShortText() {
			return l10n("pluginLoadingFailedShort", "name", filename);
		}

		public String getText() {
			return l10n("pluginLoadingFailedWithMessage", new String[] { "name", "message" }, new String[] { filename, message });
		}

		public String getTitle() {
			return l10n("pluginLoadingFailedTitle");
		}

		public Object getUserIdentifier() {
			return PluginManager.class;
		}

		public boolean isEventNotification() {
			return false;
		}

		public boolean isValid() {
			boolean success;
			synchronized(pluginWrappers) {
				success = pluginsFailedLoad.containsKey(filename);
			}
			if(!success) {
				core.alerts.unregister(this);
			}
			return success;
		}

		public void isValid(boolean validity) {
		}

		public boolean shouldUnregisterOnDismiss() {
			return true;
		}

		public boolean userCanDismiss() {
			return true;
		}


	}

	void register(FredPlugin plug, PluginInfoWrapper pi) {
		// handles FProxy? If so, register

		if(pi.isPproxyPlugin())
			registerToadlet(plug);

		if(pi.isIPDetectorPlugin())
			node.ipDetector.registerIPDetectorPlugin((FredPluginIPDetector) plug);
		if(pi.isPortForwardPlugin())
			node.ipDetector.registerPortForwardPlugin((FredPluginPortForward) plug);
		if(pi.isBandwidthIndicator())
			node.ipDetector.registerBandwidthIndicatorPlugin((FredPluginBandwidthIndicator) plug);
	}

	public void cancelRunningLoads(String filename, PluginProgress exceptFor) {
		Logger.normal(this, "Cancelling loads for plugin "+filename);
		ArrayList<PluginProgress> matches = null;
		synchronized(this) {
			for(Iterator<PluginProgress> i = startingPlugins.iterator();i.hasNext();) {
				PluginProgress progress = i.next();
				if(progress == exceptFor) continue;
				if(!filename.equals(progress.name)) continue;
				if(matches == null) matches = new ArrayList<PluginProgress>();
				matches.add(progress);
				i.remove();
			}
		}
		if(matches == null) return;
		for(PluginProgress progress : matches) {
			progress.kill();
		}
	}

	/**
	 * Returns the translation of the given key, prefixed by the short name of
	 * the current class.
	 *
	 * @param key
	 *            The key to fetch
	 * @return The translation
	 */
	private String l10n(String key) {
		return NodeL10n.getBase().getString("PluginManager." + key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("PluginManager." + key, pattern, value);
	}

	/**
	 * Returns the translation of the given key, replacing each occurence of
	 * <code>${<em>pattern</em>}</code> with <code>value</code>.
	 *
	 * @param key
	 *            The key to fetch
	 * @param patterns
	 *            The patterns to replace
	 * @param values
	 *            The values to substitute
	 * @return The translation
	 */
	private String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("PluginManager." + key, patterns, values);
	}

	private void registerToadlet(FredPlugin pl) {
		//toadletList.put(e.getStackTrace()[1].getClass().toString(), pl);
		synchronized(toadletList) {
			toadletList.put(pl.getClass().getName(), pl);
		}
		Logger.normal(this, "Added HTTP handler for /plugins/" + pl.getClass().getName() + '/');
	}

	/**
	 * Remove a plugin from the plugin list.
	 */
	public void removePlugin(PluginInfoWrapper pi) {
		synchronized(pluginWrappers) {
			if((!stopping) && !pluginWrappers.remove(pi))
				return;
		}
		core.storeConfig();
	}

	/**
	 * Removes the cached copy of the given plugin from the plugins/ directory.
	 *
	 * @param pluginSpecification
	 *            The plugin specification
	 */
	public void removeCachedCopy(String pluginSpecification) {
		if(pluginSpecification == null) {
			Logger.warning(this, "Can't remove null from cache. Ignoring");
			return;
		}

		int lastSlash = pluginSpecification.lastIndexOf('/');
		String pluginFilename;
		if(lastSlash == -1)
			/* Windows, maybe? */
			lastSlash = pluginSpecification.lastIndexOf('\\');
		File pluginDirectory = new File(node.getNodeDir(), "plugins");
		if(lastSlash == -1)
			/* it's an official plugin! */
			pluginFilename = pluginSpecification + ".jar";
		else
			pluginFilename = pluginSpecification.substring(lastSlash + 1);
		if(logDEBUG)
			Logger.minor(this, "Delete plugin - plugname: " + pluginSpecification + "filename: " + pluginFilename, new Exception("debug"));
		File[] cachedFiles = getPreviousInstances(pluginDirectory, pluginFilename);
		for (File cachedFile : cachedFiles) {
			cachedFile.delete();
		}
	}

	public void unregisterPluginToadlet(PluginInfoWrapper pi) {
		synchronized(toadletList) {
			try {
				toadletList.remove(pi.getPluginClassName());
				Logger.normal(this, "Removed HTTP handler for /plugins/" +
					pi.getPluginClassName() + '/', new Exception("debug"));
			} catch(Throwable ex) {
				Logger.error(this, "removing Plugin", ex);
			}
		}
	}

	public void addToadletSymlinks(PluginInfoWrapper pi) {
		synchronized(toadletList) {
			try {
				String targets[] = pi.getPluginToadletSymlinks();
				if(targets == null)
					return;

				for(int i = 0; i < targets.length; i++) {
					toadletList.remove(targets[i]);
					Logger.normal(this, "Removed HTTP symlink: " + targets[i] +
						" => /plugins/" + pi.getPluginClassName() + '/');
				}
			} catch(Throwable ex) {
				Logger.error(this, "removing Toadlet-link", ex);
			}
		}
	}

	public void removeToadletSymlinks(PluginInfoWrapper pi) {
		synchronized(toadletList) {
			String rm = null;
			try {
				String targets[] = pi.getPluginToadletSymlinks();
				if(targets == null)
					return;

				for(int i = 0; i < targets.length; i++) {
					rm = targets[i];
					toadletList.remove(targets[i]);
					pi.removePluginToadletSymlink(targets[i]);
					Logger.normal(this, "Removed HTTP symlink: " + targets[i] +
						" => /plugins/" + pi.getPluginClassName() + '/');
				}
			} catch(Throwable ex) {
				Logger.error(this, "removing Toadlet-link: " + rm, ex);
			}
		}
	}

	public String dumpPlugins() {
		StringBuilder out = new StringBuilder();
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size(); i++) {
				PluginInfoWrapper pi = pluginWrappers.get(i);
				out.append(pi.toString());
				out.append('\n');
			}
		}
		return out.toString();
	}

	public Set<PluginInfoWrapper> getPlugins() {
		TreeSet<PluginInfoWrapper> out = new TreeSet<PluginInfoWrapper>();
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size(); i++) {
				PluginInfoWrapper pi = pluginWrappers.get(i);
				out.add(pi);
			}
		}
		return out;
	}

	/**
	 * look for PluginInfo for a Plugin with given classname
	 * @param plugname
	 * @return the PluginInfo or null if not found
	 */
	public PluginInfoWrapper getPluginInfo(String plugname) {
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size(); i++) {
				PluginInfoWrapper pi = pluginWrappers.get(i);
				if(pi.getPluginClassName().equals(plugname) || pi.getFilename().equals(plugname))
					return pi;
			}
		}
		return null;
	}

	/**
	 * look for a FCPPlugin with given classname
	 * @param plugname
	 * @return the plugin or null if not found
	 */
	public FredPluginFCP getFCPPlugin(String plugname) {
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size(); i++) {
				PluginInfoWrapper pi = pluginWrappers.get(i);
				if(pi.isFCPPlugin() && pi.getPluginClassName().equals(plugname))
					return (FredPluginFCP) pi.plug;
			}
		}
		return null;
	}

	/**
	 * look for a Plugin with given classname
	 * @param plugname
	 * @return the true if not found
	 */
	public boolean isPluginLoaded(String plugname) {
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size(); i++) {
				PluginInfoWrapper pi = pluginWrappers.get(i);
				if(pi.getPluginClassName().equals(plugname) || pi.getFilename().equals(plugname))
					return true;
			}
		}
		return false;
	}

	/**
	 * @param plugname The plugin filename e.g. "Library" for an official plugin.
	 * @return the true if not found
	 */
	public boolean isPluginLoadedOrLoadingOrWantLoad(String plugname) {
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size(); i++) {
				PluginInfoWrapper pi = pluginWrappers.get(i);
				if(pi.getFilename().equals(plugname))
					return true;

			}
		}
		if(pluginsFailedLoad.containsKey(plugname)) return true;
		for(PluginProgress prog : startingPlugins)
			if(prog.name.equals(plugname)) return true;
		return false;
	}

	public String handleHTTPGet(String plugin, HTTPRequest request) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized(toadletList) {
			handler = toadletList.get(plugin);
		}
		if (!(handler instanceof FredPluginHTTP)) {
			throw new NotFoundPluginHTTPException("Plugin not loaded!", "/plugins");
		}

		ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
		ClassLoader pluginClassLoader = handler.getClass().getClassLoader();
		Thread.currentThread().setContextClassLoader(pluginClassLoader);
		try {
			return ((FredPluginHTTP) handler).handleHTTPGet(request);
		} finally {
			Thread.currentThread().setContextClassLoader(oldClassLoader);
		}
	}

	public String handleHTTPPost(String plugin, HTTPRequest request) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized(toadletList) {
			handler = toadletList.get(plugin);
		}
		if (handler == null)
			throw new NotFoundPluginHTTPException("Plugin '"+plugin+"' not found!", "/plugins");

		ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
		ClassLoader pluginClassLoader = handler.getClass().getClassLoader();
		Thread.currentThread().setContextClassLoader(pluginClassLoader);
		try {
		if(handler instanceof FredPluginHTTP)
			return ((FredPluginHTTP) handler).handleHTTPPost(request);
		} finally {
			Thread.currentThread().setContextClassLoader(oldClassLoader);
		}
		throw new NotFoundPluginHTTPException("Plugin '"+plugin+"' not found!", "/plugins");
	}

	public void killPlugin(String name, int maxWaitTime, boolean reloading) {
		PluginInfoWrapper pi = null;
		boolean found = false;
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size() && !found; i++) {
				pi = pluginWrappers.get(i);
				if(pi.getThreadName().equals(name)) {
					found = true;
					break;
				}
			}
		}
		if(found)
			pi.stopPlugin(this, maxWaitTime, reloading);
	}

	public void killPluginByFilename(String name, int maxWaitTime, boolean reloading) {
		PluginInfoWrapper pi = null;
		boolean found = false;
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size() && !found; i++) {
				pi = pluginWrappers.get(i);
				if(pi.getFilename().equals(name)) {
					found = true;
					break;
				}
			}
		}
		if(found)
			pi.stopPlugin(this, maxWaitTime, reloading);
	}

	public void killPluginByClass(String name, int maxWaitTime) {
		PluginInfoWrapper pi = null;
		boolean found = false;
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size() && !found; i++) {
				pi = pluginWrappers.get(i);
				if(pi.getPluginClassName().equals(name)) {
					found = true;
					break;
				}
			}
		}
		if(found)
			pi.stopPlugin(this, maxWaitTime, false);
	}

	public void killPlugin(FredPlugin plugin, int maxWaitTime) {
		PluginInfoWrapper pi = null;
		boolean found = false;
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size() && !found; i++) {
				pi = pluginWrappers.get(i);
				if(pi.plug == plugin)
					found = true;
			}
		}
		if(found)
			pi.stopPlugin(this, maxWaitTime, false);
	}

	public static class OfficialPluginDescription {
		/** The name of the plugin */
		public final String name;
		/** If true, we will download it, blocking, over HTTP, during startup (unless
		 * explicitly forbidden to use HTTP). If not, we will download it on a
		 * separate thread after startup. Both are assuming we don't have it in a file. */
		public final boolean essential;
		/** Minimum getRealVersion(). If the plugin is older than this, we will fail
		 * the load. */
		public final long minimumVersion;
		/** Does it use XML? If so, if the JVM is vulnerable, then don't load it */
		public final boolean usesXML;
		/** FreenetURI to get the latest version from */
		public final FreenetURI uri;

		OfficialPluginDescription(String name, boolean essential, long minVer, boolean usesXML, FreenetURI uri) {
			this.name = name;
			this.essential = essential;
			this.minimumVersion = minVer;
			this.usesXML = usesXML;
			this.uri = uri;
		}
	}

	public static Map<String, OfficialPluginDescription> officialPlugins = new HashMap<String, OfficialPluginDescription>();

	static {
		try {
		addOfficialPlugin("Freemail", false, 12, true, new FreenetURI("CHK@OwIWNuh0FvE4SO4HYV9TQ1GtwOrfuZZoJv4-WXp4X2Q,0jcAIHtFJOin6vke-KxUhps5jTxYeu1XvmuCZKxlQY4,AAIC--8/Freemail.jar"));
		addOfficialPlugin("HelloWorld", false, new FreenetURI("CHK@ZdTXnWV-ikkt25-y8jmhlHjCY-nikDMQwcYlWHww5eg,Usq3uRHpHuIRmMRRlNQE7BNveO1NwNI7oNKdb7cowFM,AAIC--8/HelloWorld.jar"));
		addOfficialPlugin("HelloFCP", false, new FreenetURI("CHK@0gtXJpw1QUJCmFOhoPRNqhsNbMtVw1CGVe46FUv7-e0,X8QqhtPkHoaFCUd89bgNaKxX1AV0WNBVf3sRgSF51-g,AAIC--8/HelloFCP.jar"));
		addOfficialPlugin("JSTUN", true, 2, false, new FreenetURI("CHK@STQEzqyYLPtd4mCMIXO2HV38J6jG492hyPcEjTdc1oI,ojl4TCcJpJbo1OcO8nwPjycNCt1mn6zJq3lxCNExIHI,AAIC--8/JSTUN.jar"));
		addOfficialPlugin("KeyUtils", false, 5003, false, new FreenetURI("CHK@dF9Lg7Q7Bbt4uZ4OANVaSlgrUmHmV0Pj03Y1SCaN1Fw,H5Wj5VCgI4JSusMGonUiPlAwSVAahN0Bzw2v1C6QvcA,AAIC--8/KeyUtils.jar"));
		addOfficialPlugin("MDNSDiscovery", false, 2, false, new FreenetURI("CHK@wPyhY61bsDM3OW6arFlxYX8~mBKjo~XtOTIAbT0dk88,Vr3MTAzkW5J28SJs2dTxkj6D4GVNm3u8GFsxJgzTL1M,AAIC--8/MDNSDiscovery.jar"));
		addOfficialPlugin("SNMP", false, new FreenetURI("CHK@EykJIv83UE291zONVzfXqyJYX5t66uCQJHkzQrB61MI,-npuolPZj1fcAWane2~qzRNEjKDERx52aQ5bC6NBQgw,AAIC--8/SNMP.jar"));
		addOfficialPlugin("TestGallery", false, 1, false, new FreenetURI("CHK@LfJVh1EkCr4ry0yDW74vwxkX-3nkr~ztW2z0SUZHfC0,-mz7l39dC6n0RTUiSokjC~pUDO7PWZ89miYesKH0-WA,AAIC--8/TestGallery.jar"));
		addOfficialPlugin("ThawIndexBrowser", false, 3, true, new FreenetURI("CHK@aPJ4SXq8bcDwDI4IeZcMtEk6YrYjo5KbQfDPgHbvWFw,svusKGEL8yWfAmxA0ueXvBUny0mlSReZbvpDIA7UIXk,AAIC--8/ThawIndexBrowser.jar"));
		addOfficialPlugin("UPnP", true, 10003, false, new FreenetURI("CHK@chunCVhavqu60gWdf1jlAzKyVhEx7Hy99BaDpoU~xlc,iI-VcHxkg66W8-61P-bHzJYTx9PYrI2GuGIjC4Lg8mI,AAIC--8/UPnP.jar"));
		addOfficialPlugin("XMLLibrarian", false, 25, true, new FreenetURI("CHK@PzdgNIKIzYKet2x6rk2i9TMA8R3RTKf7~H7NBB-D1m4,8rfAK29Z8LkAcmwfVgF0RBGtTxaZZBmc7qcX5AoQUEo,AAIC--8/XMLLibrarian.jar"));
		addOfficialPlugin("XMLSpider", false, 44, true, new FreenetURI("http://127.0.0.1:8888/CHK@lMQuZPmHNzyO2FxQ5s5K76jnx3R1Axei48LEgblX-zo,NInQiry56ePVwC0z8wZxPMy6ZO915R8lhLNm1pZwo8I,AAIC--8/XMLSpider.jar"));
		addOfficialPlugin("Freereader", false, 4, true, new FreenetURI("CHK@4PuSjXk4Z0Hdu04JLhdPHLyOVLljj8qVbjRn3rHVzvg,bDGYnuYj67Q4uzroPBEWAYWRk26bPzf-iQ4~Uo3S7mg,AAIC--8/Freereader.jar"));
		addOfficialPlugin("Library", false, 9, true, new FreenetURI("CHK@y56~LPDrifHVP1MeMKPdWFLKs2Ch7hQl181jhgBYKl4,41Ig57QJnAD6npm596cM1865IAVugCsErGNIoE171x8,AAIC--8/Library.jar"));
		} catch (MalformedURLException e) {
			throw new Error("Malformed hardcoded URL: "+e, e);
		}
	}

	static void addOfficialPlugin(String name, boolean usesXML) {
		officialPlugins.put(name, new OfficialPluginDescription(name, false, -1, usesXML, null));
	}

	static void addOfficialPlugin(String name, boolean usesXML, FreenetURI uri) {
		officialPlugins.put(name, new OfficialPluginDescription(name, false, -1, usesXML, uri));
	}

	static void addOfficialPlugin(String name, boolean essential, long minVer, boolean usesXML) {
		officialPlugins.put(name, new OfficialPluginDescription(name, essential, minVer, usesXML, null));
	}

	static void addOfficialPlugin(String name, boolean essential, long minVer, boolean usesXML, FreenetURI uri) {
		officialPlugins.put(name, new OfficialPluginDescription(name, essential, minVer, usesXML, uri));
	}

	/**
	 * Returns a list of the names of all available official plugins. Right now
	 * this list is hardcoded but in future we could retrieve this list from emu
	 * or from freenet itself.
	 *
	 * @return A list of all available plugin names
	 */
	public List<OfficialPluginDescription> findAvailablePlugins() {
		List<OfficialPluginDescription> availablePlugins = new ArrayList<OfficialPluginDescription>();
		availablePlugins.addAll(officialPlugins.values());
		return availablePlugins;
	}

	public OfficialPluginDescription isOfficialPlugin(String name) {
		if((name == null) || (name.trim().length() == 0))
			return null;
		List<OfficialPluginDescription> availablePlugins = findAvailablePlugins();
		for(OfficialPluginDescription desc : availablePlugins) {
			if(desc.name.equals(name))
				return desc;
		}
		return null;
	}

	/** Separate lock for plugin loading. Don't use (this) as we also use that for
	 * writing the config file, and because we do a lot inside the lock below; it
	 * must not be taken in any other circumstance. */
	private final Object pluginLoadSyncObject = new Object();

	/** All plugin updates are on a single request client. */
	public final RequestClient singleUpdaterRequestClient = new RequestClient() {

		public boolean persistent() {
			return false;
		}

		public void removeFrom(ObjectContainer container) {
			// Do nothing.
		}

	};

	public File getPluginFilename(String pluginName) {
		File pluginDirectory = new File(node.getNodeDir(), "plugins");
		if((pluginDirectory.exists() && !pluginDirectory.isDirectory()) || (!pluginDirectory.exists() && !pluginDirectory.mkdirs()))
			return null;
		return new File(pluginDirectory, pluginName + ".jar");
	}

	/**
	 * Tries to load a plugin from the given name. If the name only contains the
	 * name of a plugin it is loaded from the plugin directory, if found,
	 * otherwise it's loaded from the project server. If the name contains a
	 * complete url and the short file already exists in the plugin directory
	 * it's loaded from the plugin directory, otherwise it's retrieved from the
	 * remote server.
	 * @param pdl
	 *
	 * @param name
	 *            The specification of the plugin
	 * @return An instanciated object of the plugin
	 * @throws PluginNotFoundException
	 *             If anything goes wrong.
	 */
	private FredPlugin loadPlugin(PluginDownLoader<?> pdl, String name, PluginProgress progress) throws PluginNotFoundException {

		pdl.setSource(name);

		/* check for plugin directory. */
		File pluginDirectory = new File(node.getNodeDir(), "plugins");
		if((pluginDirectory.exists() && !pluginDirectory.isDirectory()) || (!pluginDirectory.exists() && !pluginDirectory.mkdirs())) {
			Logger.error(this, "could not create plugin directory");
			throw new PluginNotFoundException("could not create plugin directory");
		}

		/* get plugin filename. */
		String filename = pdl.getPluginName(name);
		boolean pluginIsLocal = pdl instanceof PluginDownLoaderFile;
		File pluginFile = new File(pluginDirectory, filename + "-" + System.currentTimeMillis());

		/* check for previous instances and delete them. */
		File[] filesInPluginDirectory = getPreviousInstances(pluginDirectory, filename);
		boolean first = true;
		for (File cachedFile : filesInPluginDirectory) {
			if (first && !pluginIsLocal) {
				first = false;
				pluginFile = new File(pluginDirectory, cachedFile.getName());
				continue;
			}
			first = false;
			cachedFile.delete();
		}

		boolean downloaded = false;
		/* check if file needs to be downloaded. */
		if(logMINOR)
			Logger.minor(this, "plugin file " + pluginFile.getAbsolutePath() + " exists: " + pluginFile.exists()+" downloader "+pdl+" name "+name);
		int RETRIES = 5;
		for(int i = 0; i < RETRIES; i++) {
			if(!pluginFile.exists() || pluginFile.length() == 0)
				try {
					downloaded = true;
					System.err.println("Downloading plugin "+name);
					WrapperManager.signalStarting(5*60*1000);
					File tempPluginFile = null;
					OutputStream pluginOutputStream = null;
					InputStream pluginInputStream = null;
					try {
						tempPluginFile = File.createTempFile("plugin-", ".jar", pluginDirectory);
						tempPluginFile.deleteOnExit();


						pluginOutputStream = new FileOutputStream(tempPluginFile);
						pluginInputStream = pdl.getInputStream(progress);
						byte[] buffer = new byte[1024];
						int read;
						while((read = pluginInputStream.read(buffer)) != -1) {
							pluginOutputStream.write(buffer, 0, read);
						}
						pluginOutputStream.close();
						if(tempPluginFile.length() == 0)
							throw new PluginNotFoundException("downloaded zero length file");
						if(!FileUtil.renameTo(tempPluginFile, pluginFile)) {
							Logger.error(this, "could not rename temp file to plugin file");
							throw new PluginNotFoundException("could not rename temp file to plugin file");
						}

						// try strongest first
						String testsum = null;
						String digest = pdl.getSHA256sum();
						if(digest == null) {
							digest = pdl.getSHA1sum();
						} else {
							testsum = getFileDigest(pluginFile, "SHA-256");
						}
						if(digest != null && testsum == null) {
							testsum = getFileDigest(pluginFile, "SHA-1");
						}

						if(digest != null) {
							if(!(digest.equalsIgnoreCase(testsum))) {
								Logger.error(this, "Checksum verification failed, should be " + digest + " but was " + testsum);
								throw new PluginNotFoundException("Checksum verification failed, should be " + digest + " but was " + testsum);
							}
						}

					} catch(IOException ioe1) {
						Logger.error(this, "could not load plugin", ioe1);
						if(tempPluginFile != null)
							tempPluginFile.delete();
						throw new PluginNotFoundException("could not load plugin: " + ioe1.getMessage(), ioe1);
					} finally {
						Closer.close(pluginOutputStream);
						Closer.close(pluginInputStream);
					}
				} catch(PluginNotFoundException e) {
					if(i < RETRIES - 1) {
						Logger.normal(this, "Failed to load plugin: " + e, e);
						continue;
					} else
						throw e;
				}

		cancelRunningLoads(name, progress);

		boolean remoteCodeExecVuln = node.xmlRemoteCodeExecVuln();
		// we do quite a lot inside the lock, use a dedicated one
		synchronized (pluginLoadSyncObject) {
			/* now get the manifest file. */
			JarFile pluginJarFile = null;
			String pluginMainClassName = null;
			try {
				pluginJarFile = new JarFile(pluginFile);
				Manifest manifest = pluginJarFile.getManifest();
				if(manifest == null) {
					Logger.error(this, "could not load manifest from plugin file");
					pluginFile.delete();
					if(!downloaded) continue;
					throw new PluginNotFoundException("could not load manifest from plugin file");
				}
				Attributes mainAttributes = manifest.getMainAttributes();
				if(mainAttributes == null) {
					Logger.error(this, "manifest does not contain attributes");
					pluginFile.delete();
					if(!downloaded) continue;
					throw new PluginNotFoundException("manifest does not contain attributes");
				}
				pluginMainClassName = mainAttributes.getValue("Plugin-Main-Class");
				if(pluginMainClassName == null) {
					Logger.error(this, "manifest does not contain a Plugin-Main-Class attribute");
					pluginFile.delete();
					if(!downloaded) continue;
					throw new PluginNotFoundException("manifest does not contain a Plugin-Main-Class attribute");
				}
				if(this.isPluginLoaded(pluginMainClassName)) {
					Logger.error(this, "Plugin already loaded: "+filename);
					return null;
				}

			} catch(JarException je1) {
				Logger.error(this, "could not process jar file", je1);
				pluginFile.delete();
				if(!downloaded) continue;
				throw new PluginNotFoundException("could not process jar file", je1);
			} catch(ZipException ze1) {
				Logger.error(this, "could not process jar file", ze1);
				pluginFile.delete();
				if(!downloaded) continue;
				throw new PluginNotFoundException("could not process jar file", ze1);
			} catch(IOException ioe1) {
				Logger.error(this, "error processing jar file", ioe1);
				pluginFile.delete();
				if(!downloaded) continue;
				throw new PluginNotFoundException("error procesesing jar file", ioe1);
			} finally {
				Closer.close(pluginJarFile);
			}

			try {
				JarClassLoader jarClassLoader = new JarClassLoader(pluginFile);
				Class<?> pluginMainClass = jarClassLoader.loadClass(pluginMainClassName);
				Object object = pluginMainClass.newInstance();
				if(!(object instanceof FredPlugin)) {
					Logger.error(this, "plugin main class is not a plugin");
					pluginFile.delete();
					if(!downloaded) continue;
					throw new PluginNotFoundException("plugin main class is not a plugin");
				}

				if(pdl instanceof PluginDownLoaderOfficialHTTPS ||
						pdl instanceof PluginDownLoaderOfficialFreenet) {
					System.err.println("Loading official plugin "+name);
					// Check the version after loading it!
					// Building it into the manifest would be better, in that it would
					// avoid having to unload ... but building it into the manifest is
					// problematic, specifically it involves either platform specific
					// scripts that aren't distributed and devs won't use when they
					// build locally, or executing java code, which would mean we have
					// to protect the versioning info. Either way is bad. The latter is
					// less bad if we don't auto-build.

					// Ugh, this is just as messy ... ideas???? Maybe we need to have OS
					// detection and use grep/sed on unix and find on windows???

					OfficialPluginDescription desc = officialPlugins.get(name);

					long minVer = desc.minimumVersion;
					long ver = -1;

					if(minVer != -1) {
						if(object instanceof FredPluginRealVersioned) {
							ver = ((FredPluginRealVersioned)object).getRealVersion();
						}
					}

					// FIXME l10n the PluginNotFoundException errors.
					if(ver < minVer) {
						System.err.println("Failed to load plugin "+name+" : TOO OLD: need at least version "+minVer+" but is "+ver);
						Logger.error(this, "Failed to load plugin "+name+" : TOO OLD: need at least version "+minVer+" but is "+ver);
						try {
							if(object instanceof FredPluginThreadless) {
								((FredPlugin)object).runPlugin(new PluginRespirator(node, PluginManager.this, (FredPlugin)object));
							}
						} catch (Throwable t) {
							Logger.error(this, "Failed to start plugin (to prevent NPEs) while terminating it because it is too old: "+t, t);
						}
						try {
							((FredPlugin)object).terminate();
						} catch (Throwable t) {
							Logger.error(this, "Plugin failed to terminate: "+t, t);
						}
						try {
							jarClassLoader.close();
						} catch (Throwable t) {
							Logger.error(this, "Failed to close jar classloader for plugin: "+t, t);
						}
						pluginFile.delete();
						if(!downloaded) continue;
						throw new PluginTooOldException("plugin too old: need at least version "+minVer);
					}

					if(desc.usesXML && remoteCodeExecVuln) {
						pluginFile.delete();
						throw new PluginNotFoundException("plugin cannot be loaded because your JVM is dangerously old; plugin uses XML and your JVM has remote code execution vulnerabilities in its XML parser");
					}

				}

				if(object instanceof FredPluginL10n) {
					((FredPluginL10n)object).setLanguage(NodeL10n.getBase().getSelectedLanguage());
				}

				if(object instanceof FredPluginBaseL10n) {
					((FredPluginBaseL10n)object).setLanguage(NodeL10n.getBase().getSelectedLanguage());
				}

				if(object instanceof FredPluginThemed) {
					((FredPluginThemed)object).setTheme(fproxyTheme);
				}

				return (FredPlugin) object;
			} catch(IOException ioe1) {
				Logger.error(this, "could not load plugin", ioe1);
				pluginFile.delete();
				throw new PluginNotFoundException("could not load plugin", ioe1);
			} catch(ClassNotFoundException cnfe1) {
				Logger.error(this, "could not find plugin class", cnfe1);
				pluginFile.delete();
				if(!downloaded) continue;
				throw new PluginNotFoundException("could not find plugin class", cnfe1);
			} catch(InstantiationException ie1) {
				Logger.error(this, "could not instantiate plugin", ie1);
				pluginFile.delete();
				if(!downloaded) continue;
				throw new PluginNotFoundException("could not instantiate plugin", ie1);
			} catch(IllegalAccessException iae1) {
				Logger.error(this, "could not access plugin main class", iae1);
				pluginFile.delete();
				throw new PluginNotFoundException("could not access plugin main class", iae1);
			} catch(NoClassDefFoundError ncdfe1) {
				Logger.error(this, "could not find class def, may a missing lib?", ncdfe1);
				pluginFile.delete();
				if(!downloaded) continue;
				throw new PluginNotFoundException("could not find class def, may a missing lib?", ncdfe1);
			} catch(Throwable t) {
				Logger.error(this, "unexpected error while plugin loading", t);
				pluginFile.delete();
				throw new PluginNotFoundException("unexpected error while plugin loading " + t, t);
			}
		}
		}
		return null;
	}

	/**
	 * This returns all existing instances of cached JAR files that start with
	 * the given filename followed by a dash (“-”), sorted numerically by the
	 * appendix, largest (i.e. newest) first.
	 *
	 * @param pluginDirectory
	 *            The plugin cache directory
	 * @param filename
	 *            The name of the JAR file
	 * @return All cached instances
	 */
	private File[] getPreviousInstances(File pluginDirectory, final String filename) {
		File[] cachedFiles = pluginDirectory.listFiles(new FileFilter() {

			public boolean accept(File pathname) {
				return pathname.isFile() && pathname.getName().startsWith(filename);
			}
		});
		Arrays.sort(cachedFiles, new Comparator<File>() {

			public int compare(File file1, File file2) {
				return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, extractTimestamp(file2.getName()) - extractTimestamp(file1.getName())));
			}

			private long extractTimestamp(String filename) {
				int lastIndexOfDash = filename.lastIndexOf(".jar-");
				if (lastIndexOfDash == -1) {
					return 0;
				}
				try {
					return Long.valueOf(filename.substring(lastIndexOfDash + 5));
				} catch (NumberFormatException nfe1) {
					return 0;
				}
			}
		});
		return cachedFiles;
	}

	private String getFileDigest(File file, String digest) throws PluginNotFoundException {
		final int BUFFERSIZE = 4096;
		MessageDigest hash = null;
		FileInputStream fis = null;
		BufferedInputStream bis = null;
		boolean wasFromDigest256Pool = false;
		String result;

		try {
			if ("SHA-256".equals(digest)) {
				hash = SHA256.getMessageDigest(); // grab digest from pool
				wasFromDigest256Pool = true;
			} else {
				hash = MessageDigest.getInstance(digest);
			}
			// We compute the hash
			// http://java.sun.com/developer/TechTips/1998/tt0915.html#tip2
			fis = new FileInputStream(file);
			bis = new BufferedInputStream(fis);
			int len = 0;
			byte[] buffer = new byte[BUFFERSIZE];
			while((len = bis.read(buffer)) > -1) {
				hash.update(buffer, 0, len);
			}
			result = HexUtil.bytesToHex(hash.digest());
			if (wasFromDigest256Pool)
				SHA256.returnMessageDigest(hash);
		} catch(Exception e) {
			throw new PluginNotFoundException("Error while computing hash '"+digest+"' of the downloaded plugin: " + e, e);
		} finally {
			Closer.close(bis);
			Closer.close(fis);
		}
		return result;
	}

	Ticker getTicker() {
		return node.getTicker();
	}

	/**
	 * Tracks the progress of loading and starting a plugin.
	 *
	 * @author David &lsquo;Bombe&rsquo; Roden &lt;bombe@freenetproject.org&gt;
	 * @version $Id$
	 */
	public static class PluginProgress {

		enum PROGRESS_STATE {
			DOWNLOADING,
			STARTING
		}

		/** The starting time. */
		private long startingTime = System.currentTimeMillis();
		/** The current state. */
		private PROGRESS_STATE pluginProgress;
		/** The name by which the plugin is loaded. */
		private String name;
		/** Total. Might be bytes, might be blocks. */
		private int total;
		/** Minimum for success */
		private int minSuccessful;
		/** Current value. Same units as total. */
		private int current;
		private boolean finalisedTotal;
		private int failed;
		private int fatallyFailed;
		private final PluginDownLoader<?> loader;

		/**
		 * Creates a new progress tracker for a plugin that is loaded by the
		 * given name.
		 *
		 * @param name
		 *            The name by which the plugin is loaded
		 * @param pdl
		 */
		PluginProgress(String name, PluginDownLoader<?> pdl) {
			this.name = name;
			pluginProgress = PROGRESS_STATE.DOWNLOADING;
			loader = pdl;
		}

		public void kill() {
			loader.tryCancel();
		}

		/**
		 * Returns the number of milliseconds this plugin is already being
		 * loaded.
		 *
		 * @return The time this plugin is already being loaded (in
		 *         milliseconds)
		 */
		public long getTime() {
			return System.currentTimeMillis() - startingTime;
		}

		/**
		 * Returns the name by which the plugin is loaded.
		 *
		 * @return The name by which the plugin is loaded
		 */
		public String getName() {
			return name;
		}

		/**
		 * Returns the current state of the plugin start procedure.
		 *
		 * @return The current state of the plugin
		 */
		public PROGRESS_STATE getProgress() {
			return pluginProgress;
		}

		/**
		 * Sets the current state of the plugin start procedure
		 *
		 * @param pluginProgress
		 *            The current state
		 */
		void setProgress(PROGRESS_STATE state) {
			this.pluginProgress = state;
		}

		/**
		 * If this object is one of the constants {@link #DOWNLOADING} or
		 * {@link #STARTING}, the name of those constants will be returned,
		 * otherwise a textual representation of the plugin progress is
		 * returned.
		 *
		 * @return The name of a constant, or the plugin progress
		 */
		@Override
		public String toString() {
			return "PluginProgress[name=" + name + ",startingTime=" + startingTime + ",progress=" + pluginProgress + "]";
		}

		public String toLocalisedString() {
			if(pluginProgress == PROGRESS_STATE.DOWNLOADING && total > 0)
				return NodeL10n.getBase().getString("PproxyToadlet.startingPluginStatus.downloading") + " : "+((current*100.0) / total)+"%";
			else if(pluginProgress == PROGRESS_STATE.DOWNLOADING)
				return NodeL10n.getBase().getString("PproxyToadlet.startingPluginStatus.downloading");
			else if(pluginProgress == PROGRESS_STATE.STARTING)
				return NodeL10n.getBase().getString("PproxyToadlet.startingPluginStatus.starting");
			else
				return toString();
		}

		public HTMLNode toLocalisedHTML() {
			if(pluginProgress == PROGRESS_STATE.DOWNLOADING && total > 0) {
				return QueueToadlet.createProgressCell(false, true, ClientPut.COMPRESS_STATE.WORKING, current, failed, fatallyFailed, minSuccessful, total, finalisedTotal, false);
			} else if(pluginProgress == PROGRESS_STATE.DOWNLOADING)
				return new HTMLNode("td", NodeL10n.getBase().getString("PproxyToadlet.startingPluginStatus.downloading"));
			else if(pluginProgress == PROGRESS_STATE.STARTING)
				return new HTMLNode("td", NodeL10n.getBase().getString("PproxyToadlet.startingPluginStatus.starting"));
			else
				return new HTMLNode("td", toString());
		}

		public void setDownloadProgress(int minSuccess, int current, int total, int failed, int fatallyFailed, boolean finalised) {
			this.pluginProgress = PROGRESS_STATE.DOWNLOADING;
			this.total = total;
			this.current = current;
			this.minSuccessful = minSuccess;
			this.failed = failed;
			this.fatallyFailed = fatallyFailed;
			this.finalisedTotal = finalised;
		}

		public void setDownloading() {
			this.pluginProgress = PROGRESS_STATE.DOWNLOADING;
		}
	}

	public void setFProxyTheme(final THEME cssName) {
		//if (fproxyTheme.equals(cssName)) return;
		fproxyTheme = cssName;
		synchronized(pluginWrappers) {
			for(PluginInfoWrapper pi: pluginWrappers) {
				pi.pr.getPageMaker().setTheme(cssName);
				if(pi.isThemedPlugin()) {
					final FredPluginThemed plug = (FredPluginThemed)(pi.plug);
					executor.execute(new Runnable() {
						public void run() {
							try {
								plug.setTheme(cssName);
							} catch (Throwable t) {
								Logger.error(this, "Cought Trowable in Callback", t);
							}
						}}, "Callback");
				}
			}
		}
	}

	public static void setLanguage(LANGUAGE lang) {
		if (selfinstance == null) return;
		selfinstance.setPluginLanguage(lang);
	}

	private void setPluginLanguage(final LANGUAGE lang) {
		synchronized(pluginWrappers) {
			for(PluginInfoWrapper pi: pluginWrappers) {
				if(pi.isL10nPlugin()) {
					final FredPluginL10n plug = (FredPluginL10n)(pi.plug);
					executor.execute(new Runnable() {
						public void run() {
							try {
								plug.setLanguage(lang);
							} catch (Throwable t) {
								Logger.error(this, "Cought Trowable in Callback", t);
							}
						}}, "Callback");
				} else if(pi.isBaseL10nPlugin()) {
					final FredPluginBaseL10n plug = (FredPluginBaseL10n)(pi.plug);
					executor.execute(new Runnable() {
						public void run() {
							try {
								plug.setLanguage(lang);
							} catch (Throwable t) {
								Logger.error(this, "Cought Trowable in Callback", t);
							}
						}}, "Callback");
				}
			}
		}
	}

	public THEME getFProxyTheme() {
		return fproxyTheme;
	}

	public boolean loadOfficialPluginsFromWeb() {
		return alwaysLoadOfficialPluginsFromCentralServer;
	}

	public void unregisterPlugin(PluginInfoWrapper wrapper, FredPlugin plug, boolean reloading) {
		unregisterPluginToadlet(wrapper);
		if(wrapper.isIPDetectorPlugin())
			node.ipDetector.unregisterIPDetectorPlugin((FredPluginIPDetector)plug);
		if(wrapper.isPortForwardPlugin())
			node.ipDetector.unregisterPortForwardPlugin((FredPluginPortForward)plug);
		if(wrapper.isBandwidthIndicator())
			node.ipDetector.unregisterBandwidthIndicatorPlugin((FredPluginBandwidthIndicator)plug);
		if(!reloading)
			node.nodeUpdater.stopPluginUpdater(wrapper.getFilename());
	}
}
