/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker.THEME;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.l10n.L10n.LANGUAGE;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.node.Ticker;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.JarClassLoader;
import freenet.support.Logger;
import freenet.support.SerialExecutor;
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
	private final Vector<String> pluginsFailedLoad;
	final Node node;
	private final NodeClientCore core;
	SubConfig pmconfig;
	private boolean logMINOR;
	private boolean logDEBUG;
	private final HighLevelSimpleClient client;
	
	private static PluginManager selfinstance = null;

	private THEME fproxyTheme;
	
	private final SerialExecutor executor;

	public PluginManager(Node node) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		// config 

		toadletList = new HashMap<String, FredPlugin>();
		pluginWrappers = new Vector<PluginInfoWrapper>();
		pluginsFailedLoad = new Vector<String>();
		this.node = node;
		this.core = node.clientCore;

		if(logMINOR)
			Logger.minor(this, "Starting Plugin Manager");

		if(logDEBUG)
			Logger.debug(this, "Initialize Plugin Manager config");

		client = core.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, true);

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
//				//throw new InvalidConfigValueException(L10n.getString("PluginManager.cannotSetOnceLoaded"));
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
					throw new InvalidConfigValueException(L10n.getString("PluginManager.cannotSetOnceLoaded"));
				}

			@Override
				public boolean isReadOnly() {
					return true;
				}
			});

		toStart = pmconfig.getStringArr("loadplugin");
		
		pmconfig.finishedInitialization();
		
		fproxyTheme = THEME.themeFromName(node.config.get("fproxy").getString("css"));
		selfinstance = this;
	}
	
	private boolean started;
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

	private String[] getConfigLoadString() {
		Vector<String> v = new Vector<String>();
		
		synchronized(pluginWrappers) {
			if(!started) return toStart;
			for(PluginInfoWrapper pi : pluginWrappers) {
				v.add(pi.getFilename());
			}
			for(String s : pluginsFailedLoad) {
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
	public void startPluginAuto(final String pluginname, boolean store) {

		if(isOfficialPlugin(pluginname)) {
			startPluginOfficial(pluginname, store);
			return;
		}

		try {
			new FreenetURI(pluginname); // test for MalformedURLException 
			startPluginFreenet(pluginname, store);
			return;
		} catch(MalformedURLException e) {
			// not a freenet key
		}

		File[] roots = File.listRoots();
		for(File f : roots) {
			if(pluginname.startsWith(f.getName()) && new File(pluginname).exists()) {
				startPluginFile(pluginname, store);
				return;
			}
		}

		startPluginURL(pluginname, store);
	}

	public void startPluginOfficial(final String pluginname, boolean store) {
		realStartPlugin(new PluginDownLoaderOfficial(), pluginname, store);
	}

	public void startPluginFile(final String filename, boolean store) {
		realStartPlugin(new PluginDownLoaderFile(), filename, store);
	}

	public void startPluginURL(final String filename, boolean store) {
		realStartPlugin(new PluginDownLoaderURL(), filename, store);
	}

	public void startPluginFreenet(final String filename, boolean store) {
		realStartPlugin(new PluginDownLoaderFreenet(client), filename, store);
	}

	private void realStartPlugin(final PluginDownLoader<?> pdl, final String filename, final boolean store) {
		if(filename.trim().length() == 0)
			return;
		final PluginProgress pluginProgress = new PluginProgress(filename);
		synchronized(startingPlugins) {
			startingPlugins.add(pluginProgress);
		}
				Logger.normal(this, "Loading plugin: " + filename);
				FredPlugin plug;
				try {
					plug = loadPlugin(pdl, filename);
					if(plug == null) return; // Already loaded
					pluginProgress.setProgress(PluginProgress.STARTING);
					PluginInfoWrapper pi = PluginHandler.startPlugin(PluginManager.this, filename, plug, new PluginRespirator(node, PluginManager.this, plug));
					synchronized(pluginWrappers) {
						pluginWrappers.add(pi);
						pluginsFailedLoad.remove(filename);
					}
					Logger.normal(this, "Plugin loaded: " + filename);
				} catch(PluginNotFoundException e) {
					Logger.normal(this, "Loading plugin failed (" + filename + ')', e);
					String message = e.getMessage();
					synchronized(pluginWrappers) {
						pluginsFailedLoad.add(filename);
					}
					core.alerts.register(new PluginLoadFailedUserAlert(filename, pdl instanceof PluginDownLoaderOfficial, message));
				} catch(UnsupportedClassVersionError e) {
					Logger.error(this, "Could not load plugin " + filename + " : " + e, e);
					System.err.println("Could not load plugin " + filename + " : " + e);
					e.printStackTrace();
					System.err.println("Plugin " + filename + " appears to require a later JVM");
					Logger.error(this, "Plugin " + filename + " appears to require a later JVM");
					synchronized(pluginWrappers) {
						pluginsFailedLoad.add(filename);
					}
					core.alerts.register(new PluginLoadFailedUserAlert(filename, pdl instanceof PluginDownLoaderOfficial, l10n("pluginReqNewerJVMTitle", "name", filename)));
				} finally {
					synchronized(startingPlugins) {
						startingPlugins.remove(pluginProgress);
					}
				}
				/* try not to destroy the config. */
				synchronized(this) {
					if(store)
						core.storeConfig();
				}
	}

	class PluginLoadFailedUserAlert implements UserAlert {

		final String filename;
		final String message;
		final boolean official;
		
		public PluginLoadFailedUserAlert(String filename, boolean official, String message) {
			this.filename = filename;
			this.official = official;
			this.message = message;
		}

		public String dismissButtonText() {
			return l10n("deleteFailedPluginButton");
		}
		
		public void onDismiss() {
			synchronized(pluginWrappers) {
				pluginsFailedLoad.remove(filename);
			}
		}

		public String anchor() {
			return "pluginfailed:"+filename;
		}

		public HTMLNode getHTMLText() {
			HTMLNode p = new HTMLNode("p");
			p.addChild("#", l10n("pluginLoadingFailedWithMessage", new String[] { "name", "message" }, new String[] { filename, message }));
			
			if(official) {
				HTMLNode reloadForm = p.addChild("form", new String[] { "action", "method" }, new String[] { "/plugins/", "post" });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "plugin-name", filename });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-official", l10n("officialPluginLoadFailedTryAgain") });
			}
			
			return p;
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
				success = pluginsFailedLoad.contains(filename);
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

	/**
	 * Returns the translation of the given key, prefixed by the short name of
	 * the current class.
	 * 
	 * @param key
	 *            The key to fetch
	 * @return The translation
	 */
	private String l10n(String key) {
		return L10n.getString("PluginManager." + key);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("PluginManager." + key, pattern, value);
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
		return L10n.getString("PluginManager." + key, patterns, values);
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
			if(!pluginWrappers.remove(pi))
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
		int lastSlash = pluginSpecification.lastIndexOf('/');
		File pluginFile;
		if(lastSlash == -1)
			/* Windows, maybe? */
			lastSlash = pluginSpecification.lastIndexOf('\\');
		File pluginDirectory = new File(node.getNodeDir(), "plugins");
		if(lastSlash == -1)
			/* it's an official plugin! */
			pluginFile = new File(pluginDirectory, pluginSpecification + ".jar");
		else
			pluginFile = new File(pluginDirectory, pluginSpecification.substring(lastSlash + 1));
		if(logDEBUG)
			Logger.minor(this, "Delete plugin - plugname: " + pluginSpecification + "filename: " + pluginFile.getAbsolutePath(), new Exception("debug"));
		if(pluginFile.exists())
			pluginFile.delete();
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
		HashSet<PluginInfoWrapper> out = new HashSet<PluginInfoWrapper>();
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size(); i++) {
				PluginInfoWrapper pi = pluginWrappers.get(i);
				out.add(pi);
			}
		}
		return out;
	}

	/**
	 * look for PluginInfo for a FCPPlugin with given classname
	 * @param plugname
	 * @return the PluginInfo or null if not found
	 */
	public PluginInfoWrapper getFCPPluginInfo(String plugname) {
		synchronized(pluginWrappers) {
			for(int i = 0; i < pluginWrappers.size(); i++) {
				PluginInfoWrapper pi = pluginWrappers.get(i);
				if(pi.getPluginClassName().equals(plugname))
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
				if(pi.getPluginClassName().equals(plugname))
					return true;
			}
		}
		return false;
	}

	public String handleHTTPGet(String plugin, HTTPRequest request) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized(toadletList) {
			handler = toadletList.get(plugin);
		}
		/*if (handler == null)
		return null;
		 */

		ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
		ClassLoader pluginClassLoader = handler.getClass().getClassLoader();
		Thread.currentThread().setContextClassLoader(pluginClassLoader);
		try {
		if(handler instanceof FredPluginHTTP)
			return ((FredPluginHTTP) handler).handleHTTPGet(request);
		} finally {
			Thread.currentThread().setContextClassLoader(oldClassLoader);
		}

		throw new NotFoundPluginHTTPException("Plugin not found!", "/plugins");
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

	public void killPlugin(String name, int maxWaitTime) {
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
			pi.stopPlugin(this, maxWaitTime);
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
			pi.stopPlugin(this, maxWaitTime);
	}

	private static class OfficialPluginDescription {
		/** The name of the plugin */
		final String name;
		/** If true, we will download it, blocking, over HTTP, during startup (unless
		 * explicitly forbidden to use HTTP). If not, we will download it on a 
		 * separate thread after startup. Both are assuming we don't have it in a file. */
		final boolean essential;
		/** Minimum getRealVersion(). If the plugin is older than this, we will fail
		 * the load. */
		final long minimumVersion;
		
		OfficialPluginDescription(String name, boolean essential, long minVer) {
			this.name = name;
			this.essential = essential;
			this.minimumVersion = minVer;
		}
	}
	
	static Map<String, OfficialPluginDescription> officialPlugins = new HashMap<String, OfficialPluginDescription>();
	
	static {
		addOfficialPlugin("Echo");
		addOfficialPlugin("Freemail");
		addOfficialPlugin("HelloWorld");
		addOfficialPlugin("HelloFCP");
		addOfficialPlugin("JSTUN", true, -1);
		addOfficialPlugin("KeyExplorer", false, 1);
		addOfficialPlugin("MDNSDiscovery");
		addOfficialPlugin("SNMP");
		addOfficialPlugin("TestGallery");
		addOfficialPlugin("ThawIndexBrowser", false, 1);
		addOfficialPlugin("UPnP");
		addOfficialPlugin("XMLLibrarian");
		addOfficialPlugin("XMLSpider", false, 35);
	}
	
	static void addOfficialPlugin(String name) {
		officialPlugins.put(name, new OfficialPluginDescription(name, false, -1));
	}
	
	static void addOfficialPlugin(String name, boolean essential, long minVer) {
		officialPlugins.put(name, new OfficialPluginDescription(name, essential, minVer));
	}
	
	/**
	 * Returns a list of the names of all available official plugins. Right now
	 * this list is hardcoded but in future we could retrieve this list from emu
	 * or from freenet itself.
	 * 
	 * @return A list of all available plugin names
	 */
	public List<String> findAvailablePlugins() {
		List<String> availablePlugins = new ArrayList<String>();
		availablePlugins.addAll(officialPlugins.keySet());
		return availablePlugins;
	}

	public boolean isOfficialPlugin(String name) {
		if((name == null) || (name.trim().length() == 0))
			return false;
		List<String> availablePlugins = findAvailablePlugins();
		for(String n : availablePlugins) {
			if(n.equals(name))
				return true;
		}
		return false;
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
	private FredPlugin loadPlugin(PluginDownLoader<?> pdl, String name) throws PluginNotFoundException {

		pdl.setSource(name);

		/* check for plugin directory. */
		File pluginDirectory = new File(node.getNodeDir(), "plugins");
		if((pluginDirectory.exists() && !pluginDirectory.isDirectory()) || (!pluginDirectory.exists() && !pluginDirectory.mkdirs())) {
			Logger.error(this, "could not create plugin directory");
			throw new PluginNotFoundException("could not create plugin directory");
		}

		/* get plugin filename. */
		String filename = pdl.getPluginName(name);
		File pluginFile = new File(pluginDirectory, filename);

		/* check if file needs to be downloaded. */
		if(logMINOR)
			Logger.minor(this, "plugin file " + pluginFile.getAbsolutePath() + " exists: " + pluginFile.exists()+" downloader "+pdl+" name "+name);
		int RETRIES = 5;
		for(int i = 0; i < RETRIES; i++) {
			if(!pluginFile.exists() || pluginFile.length() == 0)
				try {
					System.err.println("Downloading plugin "+name);
					WrapperManager.signalStarting(5*60*1000);
					File tempPluginFile = null;
					OutputStream pluginOutputStream = null;
					InputStream pluginInputStream = null;
					try {
						tempPluginFile = File.createTempFile("plugin-", ".jar", pluginDirectory);
						tempPluginFile.deleteOnExit();


						pluginOutputStream = new FileOutputStream(tempPluginFile);
						pluginInputStream = pdl.getInputStream();
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

						String digest = pdl.getSHA1sum();
						if(digest != null) {
							String testsum = getFileSHA1(pluginFile);
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
		}
		
		// synchronized(this), not (pluginwrappers)
		synchronized (this) {
			if(this.isPluginLoaded(filename)) {
				Logger.error(this, "Plugin already loaded: "+filename);
				return null;
			}

			/* now get the manifest file. */
			JarFile pluginJarFile = null;
			String pluginMainClassName = null;
			try {
				pluginJarFile = new JarFile(pluginFile);
				Manifest manifest = pluginJarFile.getManifest();
				if(manifest == null) {
					Logger.error(this, "could not load manifest from plugin file");
					pluginFile.delete();
					throw new PluginNotFoundException("could not load manifest from plugin file");
				}
				Attributes mainAttributes = manifest.getMainAttributes();
				if(mainAttributes == null) {
					Logger.error(this, "manifest does not contain attributes");
					pluginFile.delete();
					throw new PluginNotFoundException("manifest does not contain attributes");
				}
				pluginMainClassName = mainAttributes.getValue("Plugin-Main-Class");
				if(pluginMainClassName == null) {
					Logger.error(this, "manifest does not contain a Plugin-Main-Class attribute");
					pluginFile.delete();
					throw new PluginNotFoundException("manifest does not contain a Plugin-Main-Class attribute");
				}
			} catch(JarException je1) {
				Logger.error(this, "could not process jar file", je1);
				pluginFile.delete();
				throw new PluginNotFoundException("could not process jar file", je1);
			} catch(ZipException ze1) {
				Logger.error(this, "could not process jar file", ze1);
				pluginFile.delete();
				throw new PluginNotFoundException("could not process jar file", ze1);
			} catch(IOException ioe1) {
				Logger.error(this, "error processing jar file", ioe1);
				pluginFile.delete();
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
					throw new PluginNotFoundException("plugin main class is not a plugin");
				}
				if(object instanceof FredPluginWithClassLoader) {
					((FredPluginWithClassLoader)object).setClassLoader(jarClassLoader);
				}
				
				if(pdl instanceof PluginDownLoaderOfficial) {
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
					System.err.println("Minimum version is: "+minVer);
					long ver = -1;
					
					if(minVer != -1) {
						
						if(object instanceof FredPluginRealVersioned) {
							ver = ((FredPluginRealVersioned)object).getRealVersion();
						}
					}
					System.err.println("Actual version is: "+ver);
					
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
						throw new PluginNotFoundException("plugin too old: need at least version "+minVer);
					}

				}
				
				return (FredPlugin) object;
			} catch(IOException ioe1) {
				Logger.error(this, "could not load plugin", ioe1);
				pluginFile.delete();
				throw new PluginNotFoundException("could not load plugin", ioe1);
			} catch(ClassNotFoundException cnfe1) {
				Logger.error(this, "could not find plugin class", cnfe1);
				pluginFile.delete();
				throw new PluginNotFoundException("could not find plugin class", cnfe1);
			} catch(InstantiationException ie1) {
				Logger.error(this, "could not instantiate plugin", ie1);
				pluginFile.delete();
				throw new PluginNotFoundException("could not instantiate plugin", ie1);
			} catch(IllegalAccessException iae1) {
				Logger.error(this, "could not access plugin main class", iae1);
				pluginFile.delete();
				throw new PluginNotFoundException("could not access plugin main class", iae1);
			} catch(NoClassDefFoundError ncdfe1) {
				Logger.error(this, "could not find class def, may a missing lib?", ncdfe1);
				pluginFile.delete();
				throw new PluginNotFoundException("could not find class def, may a missing lib?", ncdfe1);
			} catch(Throwable t) {
				Logger.error(this, "unexcpected error while plugin loading", t);
				pluginFile.delete();
				throw new PluginNotFoundException("unexcpected error while plugin loading " + t, t);
			}
		}
	}

	private String getFileSHA1(File file) throws PluginNotFoundException {
		final int BUFFERSIZE = 4096;
		MessageDigest hash = null;
		FileInputStream fis = null;
		BufferedInputStream bis = null;

		try {
			hash = MessageDigest.getInstance("SHA-1");
			// We compute the hash
			// http://java.sun.com/developer/TechTips/1998/tt0915.html#tip2
			fis = new FileInputStream(file);
			bis = new BufferedInputStream(fis);
			int len = 0;
			byte[] buffer = new byte[BUFFERSIZE];
			while((len = bis.read(buffer)) > -1) {
				hash.update(buffer, 0, len);
			}
		} catch(Exception e) {
			throw new PluginNotFoundException("Error while computing sha1 hash of the downloaded plugin: " + e, e);
		} finally {
			Closer.close(bis);
			Closer.close(fis);
		}
		return HexUtil.bytesToHex(hash.digest());
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

		/** State for downloading. */
		public static final PluginProgress DOWNLOADING = new PluginProgress();
		/** State for starting. */
		public static final PluginProgress STARTING = new PluginProgress();
		/** The starting time. */
		private long startingTime = System.currentTimeMillis();
		/** The current state. */
		private PluginProgress pluginProgress;
		/** The name by which the plugin is loaded. */
		private String name;

		/**
		 * Private constructor for state constants.
		 */
		private PluginProgress() {
		}

		/**
		 * Creates a new progress tracker for a plugin that is loaded by the
		 * given name.
		 * 
		 * @param name
		 *            The name by which the plugin is loaded
		 */
		PluginProgress(String name) {
			this.name = name;
			pluginProgress = DOWNLOADING;
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
		public PluginProgress getProgress() {
			return pluginProgress;
		}

		/**
		 * Sets the current state of the plugin start procedure
		 * 
		 * @param pluginProgress
		 *            The current state
		 */
		void setProgress(PluginProgress pluginProgress) {
			this.pluginProgress = pluginProgress;
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
			if(this == DOWNLOADING)
				return "downloading";
			else if(this == STARTING)
				return "starting";
			return "PluginProgress[name=" + name + ",startingTime=" + startingTime + ",progress=" + pluginProgress + "]";
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
				}
			}
		}
	}

	public THEME getFProxyTheme() {
		return fproxyTheme;
	}
}
