/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipException;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.Ticker;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.JarClassLoader;
import freenet.support.Logger;
import freenet.support.URIPreEncoder;
import freenet.support.api.HTTPRequest;
import freenet.support.api.StringArrCallback;
import freenet.support.io.Closer;
import freenet.support.io.StreamCopier;

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

	private final HashMap toadletList;

	/* All currently starting plugins. */
	private final Set/* <PluginProgress> */startingPlugins = new HashSet/* <PluginProgress> */();

	private final Vector/* <PluginInfoWrapper> */pluginWrappers;
	private PluginRespirator pluginRespirator = null;
	final Node node;
	private final NodeClientCore core;
	SubConfig pmconfig;
	private boolean logMINOR;

	public PluginManager(Node node) {
		toadletList = new HashMap();
		pluginWrappers = new Vector();
		this.node = node;
		this.core = node.clientCore;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		pluginRespirator = new PluginRespirator(node, this);

		pmconfig = new SubConfig("pluginmanager", node.config);
		// Start plugins in the config
		pmconfig.register("loadplugin", null, 9, true, false, "PluginManager.loadedOnStartup", "PluginManager.loadedOnStartupLong",
				new StringArrCallback() {
			public String[] get() {
				return getConfigLoadString();
			}

			public void set(String[] val) throws InvalidConfigValueException {
				//if(storeDir.equals(new File(val))) return;
				// FIXME
				throw new InvalidConfigValueException(L10n.getString("PluginManager.cannotSetOnceLoaded"));
			}
		});

		String fns[] = pmconfig.getStringArr("loadplugin");
		if (fns != null) {
			for (int i = 0; i < fns.length; i++) {
				String name = fns[i];
				boolean refresh = name.endsWith("*");
				if (refresh) {
					name = name.substring(0, name.length() - 1);
				}
				startPlugin(name, refresh, false);
			}
		}

		pmconfig.finishedInitialization();
		/*System.err.println("=================================");
		  pmconfig.finishedInitialization();
		  fns = pmconfig.getStringArr("loadplugin");
		  for (int i = 0 ; i < fns.length ; i++)
		  System.err.println("Load: " + StringArrOption.decode(fns[i]));
		  System.err.println("=================================");
		 */
	}

	private String[] getConfigLoadString() {
		try{
			Iterator it = getPlugins().iterator();

			Vector v = new Vector();

			while (it.hasNext()) {
				PluginInfoWrapper pluginInfoWrapper = (PluginInfoWrapper) it.next();
				v.add(pluginInfoWrapper.getFilename() + (pluginInfoWrapper.isAutoRefresh() ? "*" : ""));
			}

			return (String[]) v.toArray(new String[v.size()]);
		}catch (NullPointerException e){
			Logger.error(this, "error while loading plugins: disabling them:"+e);
			return new String[0];
		}
	}

	/**
	 * Returns a set of all currently starting plugins.
	 * 
	 * @return All currently starting plugins
	 */
	public Set/* <PluginProgess> */getStartingPlugins() {
		synchronized (startingPlugins) {
			return new HashSet/* <PluginProgress> */(startingPlugins);
		}
	}

	public void startPlugin(final String filename, final boolean refresh, final boolean store) {
		if (filename.trim().length() == 0)
			return;
		final PluginProgress pluginProgress = new PluginProgress(filename);
		synchronized (startingPlugins) {
			startingPlugins.add(pluginProgress);
		}
		node.executor.execute(new Runnable() {

			public void run() {
				Logger.normal(this, "Loading plugin: " + filename);
				FredPlugin plug;
				try {
					plug = loadPlugin(filename, refresh);
					pluginProgress.setProgress(PluginProgress.STARTING);
					PluginInfoWrapper pi = PluginHandler.startPlugin(PluginManager.this, filename, plug, pluginRespirator, refresh);
					synchronized (pluginWrappers) {
						pluginWrappers.add(pi);
					}
					Logger.normal(this, "Plugin loaded: " + filename);
				} catch (PluginNotFoundException e) {
					Logger.normal(this, "Loading plugin failed (" + filename + ')', e);
					String message = e.getMessage();
					core.alerts.register(new SimpleUserAlert(true, l10n("pluginLoadingFailedTitle"), l10n("pluginLoadingFailedWithMessage", new String[] { "name", "message" }, new String[] { filename, message }), UserAlert.ERROR, PluginManager.class));
				} catch (UnsupportedClassVersionError e) {
					Logger.error(this, "Could not load plugin " + filename + " : " + e, e);
					System.err.println("Could not load plugin " + filename + " : " + e);
					e.printStackTrace();
					String jvmVersion = System.getProperty("java.vm.version");
					if (jvmVersion.startsWith("1.4.") || jvmVersion.equals("1.4")) {
						System.err.println("Plugin " + filename + " appears to require a later JVM");
						Logger.error(this, "Plugin " + filename + " appears to require a later JVM");
						core.alerts.register(new SimpleUserAlert(true, l10n("pluginReqNewerJVMTitle", "name", filename), l10n("pluginReqNewerJVM", "name", filename), UserAlert.ERROR, PluginManager.class));
					}
				} finally {
					synchronized (startingPlugins) {
						startingPlugins.remove(pluginProgress);
					}
				}
				/* try not to destroy the config. */
				synchronized (this) {
					if (store)
						core.storeConfig();
				}
			}
		}, "Plugin Starter");
	}

	void register(FredPlugin plug, PluginInfoWrapper pi) {
		// handles FProxy? If so, register

		if (pi.isPproxyPlugin())
			registerToadlet(plug);

		if(pi.isIPDetectorPlugin()) {
			node.ipDetector.registerIPDetectorPlugin((FredPluginIPDetector) plug);
		}
		if(pi.isPortForwardPlugin()) {
			node.ipDetector.registerPortForwardPlugin((FredPluginPortForward) plug);
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
		return L10n.getString("PluginManager." + key);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("PluginManager."+key, pattern, value);
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

	private void registerToadlet(FredPlugin pl){
		//toadletList.put(e.getStackTrace()[1].getClass().toString(), pl);
		synchronized (toadletList) {
			toadletList.put(pl.getClass().getName(), pl);
		}
		Logger.normal(this, "Added HTTP handler for /plugins/"+pl.getClass().getName()+ '/');
	}

	/**
	 * Remove a plugin from the plugin list.
	 */
	public void removePlugin(PluginInfoWrapper pi) {
		synchronized (pluginWrappers) {
			if(!pluginWrappers.remove(pi)) return;
		}
		core.storeConfig();
	}

	public void unregisterPluginToadlet(PluginInfoWrapper pi) {
		synchronized (toadletList) {
			try {
				toadletList.remove(pi.getPluginClassName());
				Logger.normal(this, "Removed HTTP handler for /plugins/"+
						pi.getPluginClassName()+ '/', new Exception("debug"));
			} catch (Throwable ex) {
				Logger.error(this, "removing Plugin", ex);
			}
		}
	}

	public void addToadletSymlinks(PluginInfoWrapper pi) {
		synchronized (toadletList) {
			try {
				String targets[] = pi.getPluginToadletSymlinks();
				if (targets == null)
					return;

				for (int i = 0 ; i < targets.length ; i++) {
					toadletList.remove(targets[i]);
					Logger.normal(this, "Removed HTTP symlink: " + targets[i] +
							" => /plugins/"+pi.getPluginClassName()+ '/');
				}
			} catch (Throwable ex) {
				Logger.error(this, "removing Toadlet-link", ex);
			}
		}
	}

	public void removeToadletSymlinks(PluginInfoWrapper pi) {
		synchronized (toadletList) {
			String rm = null;
			try {
				String targets[] = pi.getPluginToadletSymlinks();
				if (targets == null)
					return;

				for (int i = 0 ; i < targets.length ; i++) {
					rm = targets[i];
					toadletList.remove(targets[i]);
					pi.removePluginToadletSymlink(targets[i]);
					Logger.normal(this, "Removed HTTP symlink: " + targets[i] +
							" => /plugins/"+pi.getPluginClassName()+ '/');
				}
			} catch (Throwable ex) {
				Logger.error(this, "removing Toadlet-link: " + rm, ex);
			}
		}
	}

	public String dumpPlugins() {
		StringBuffer out= new StringBuffer();
		synchronized (pluginWrappers) {
			for(int i=0;i<pluginWrappers.size();i++) {
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginWrappers.get(i);
				out.append(pi.toString());
				out.append('\n');
			}
		}
		return out.toString();
	}

	public Set getPlugins() {
		HashSet out = new HashSet();
		synchronized (pluginWrappers) {
			for(int i=0;i<pluginWrappers.size();i++) {
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginWrappers.get(i);
				out.add(pi);
			}
		}
		return out;
	}

	public String handleHTTPGet(String plugin, HTTPRequest request) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized (toadletList) {
			handler = (FredPlugin)toadletList.get(plugin);
		}
		/*if (handler == null)
		  return null;
		 */

		if (handler instanceof FredPluginHTTP)
			return ((FredPluginHTTP)handler).handleHTTPGet(request);

		throw new NotFoundPluginHTTPException("Plugin not found!", "/plugins");
	}

	public String handleHTTPPost(String plugin, HTTPRequest request) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized (toadletList) {
			handler = (FredPlugin)toadletList.get(plugin);
		}
		/*if (handler == null)
		  return null;
		 */

		if (handler instanceof FredPluginHTTP)
			return ((FredPluginHTTP)handler).handleHTTPPost(request);

		throw new NotFoundPluginHTTPException("Plugin not found!", "/plugins");
	}

	public void killPlugin(String name, int maxWaitTime) {
		PluginInfoWrapper pi = null;
		boolean found = false;
		synchronized (pluginWrappers) {
			for(int i=0;i<pluginWrappers.size() && !found;i++) {
				pi = (PluginInfoWrapper) pluginWrappers.get(i);
				if (pi.getThreadName().equals(name)) {
					found = true;
					break;
				}
			}
		}
		if (found) {
			pi.stopPlugin(this, maxWaitTime);
		}
	}

	public void killPlugin(FredPlugin plugin, int maxWaitTime) {
		PluginInfoWrapper pi = null;
		boolean found = false;
		synchronized (pluginWrappers) {
			for(int i=0;i<pluginWrappers.size() && !found;i++) {
				pi = (PluginInfoWrapper) pluginWrappers.get(i);
				if (pi.plug == plugin) {
					found = true;
				}
			}
		}
		if (found) {
			pi.stopPlugin(this, maxWaitTime);
		}
	}

	/**
	 * Tries to load a plugin from the given name. If the name only contains the
	 * name of a plugin and the plugin should not be refreshed on startup it is
	 * loaded from the plugin directory, if found, otherwise it's refresh from
	 * the project server. If the name contains a complete url and the short
	 * file already exists in the plugin directory and the plugin should not be
	 * refreshed, it's loaded from the plugin directory, otherwise it's
	 * retrieved from the remote server.
	 * 
	 * @param name
	 *            The specification of the plugin
	 * @param refresh
	 *            Whether the file should be refreshed on startup
	 * @return An instanciated object of the plugin
	 * @throws PluginNotFoundException
	 *             If anything goes wrong.
	 */
	private FredPlugin loadPlugin(String name, boolean refresh) throws PluginNotFoundException {
		/* check if name contains a URL. */
		URL pluginUrl = null;
		try {
			pluginUrl = new URL(name);
		} catch (MalformedURLException mue1) {
		}
		if (pluginUrl == null) {
			try {
				pluginUrl = new URL("http://downloads.freenetproject.org/alpha/plugins/" + name + ".jar.url");
			} catch (MalformedURLException mue1) {
				Logger.error(this, "could not build plugin url for " + name, mue1);
				throw new PluginNotFoundException("could not build plugin url for " + name, mue1);
			}
		}

		/* check for plugin directory. */
		File pluginDirectory = new File("plugins");
		if ((pluginDirectory.exists() && !pluginDirectory.isDirectory()) || (!pluginDirectory.exists() && !pluginDirectory.mkdirs())) {
			Logger.error(this, "could not create plugin directory");
			throw new PluginNotFoundException("could not create plugin directory");
		}

		/* get plugin filename. */
		String completeFilename = pluginUrl.getPath();
		String filename = completeFilename.substring(completeFilename.lastIndexOf('/') + 1);
		File pluginFile = new File(pluginDirectory, filename);

		/* check if file needs to be downloaded. */
		if (logMINOR) {
			Logger.minor(this, "plugin file " + pluginFile.getAbsolutePath() + " exists: " + pluginFile.exists());
		}
		if (refresh || !pluginFile.exists()) {
			File tempPluginFile = null;
			OutputStream pluginOutputStream = null;
			URLConnection urlConnection = null;
			InputStream pluginInputStream = null;
			try {
				tempPluginFile = File.createTempFile("plugin-", ".jar", pluginDirectory);
				pluginOutputStream = new FileOutputStream(tempPluginFile);
				urlConnection = pluginUrl.openConnection();
				urlConnection.setUseCaches(false);
				urlConnection.setAllowUserInteraction(false);
				urlConnection.connect();
				pluginInputStream = urlConnection.getInputStream();
				byte[] buffer = new byte[1024];
				int read;
				while ((read = pluginInputStream.read(buffer)) != -1) {
					System.out.println("read " + read + " bytes");
					pluginOutputStream.write(buffer, 0, read);
				}
			} catch (IOException ioe1) {
				Logger.error(this, "could not load plugin", ioe1);
				if (tempPluginFile != null) {
					tempPluginFile.delete();
				}
				throw new PluginNotFoundException("could not load plugin: " + ioe1.getMessage(), ioe1);
			} finally {
				Closer.close(pluginOutputStream);
				Closer.close(pluginInputStream);
			}
			/* move temp jar to final jar. */
			if (pluginFile.exists()) {
				if (!pluginFile.delete()) {
					Logger.error(this, "could not remove old plugin file");
					throw new PluginNotFoundException("could not remove old plugin file");
				}
			}
			if (!tempPluginFile.renameTo(pluginFile)) {
				Logger.error(this, "could not rename temp file to plugin file");
				throw new PluginNotFoundException("could not rename temp file to plugin file");
			}
		}

		/* now get the manifest file. */
		JarFile pluginJarFile = null;
		String pluginMainClassName = null;
		try {
			pluginJarFile = new JarFile(pluginFile);
			Manifest manifest = pluginJarFile.getManifest();
			if (manifest == null) {
				Logger.error(this, "could not load manifest from plugin file");
				throw new PluginNotFoundException("could not load manifest from plugin file");
			}
			Attributes pluginMainClassAttributes = manifest.getMainAttributes();
			if (pluginMainClassAttributes == null) {
				Logger.error(this, "manifest does not contain Plugin-Main-Class attribute");
				throw new PluginNotFoundException("manifest does not contain Plugin-Main-Class attribute");
			}
			pluginMainClassName = pluginMainClassAttributes.getValue("Plugin-Main-Class");
		} catch (JarException je1) {
			Logger.error(this, "could not process jar file", je1);
			throw new PluginNotFoundException("could not process jar file", je1);
		} catch (ZipException ze1) {
			Logger.error(this, "could not process jar file", ze1);
			throw new PluginNotFoundException("could not process jar file", ze1);
		} catch (IOException ioe1) {
			Logger.error(this, "error processing jar file", ioe1);
			throw new PluginNotFoundException("error procesesing jar file", ioe1);
		} finally {
			Closer.close(pluginJarFile);
		}

		try {
			JarClassLoader jarClassLoader = new JarClassLoader(pluginFile);
			Class pluginMainClass = jarClassLoader.loadClass(pluginMainClassName);
			Object object = pluginMainClass.newInstance();
			if (!(object instanceof FredPlugin)) {
				Logger.error(this, "plugin main class is not a plugin");
				throw new PluginNotFoundException("plugin main class is not a plugin");
			}
			return (FredPlugin) object;
		} catch (IOException ioe1) {
			Logger.error(this, "could not load plugin", ioe1);
			throw new PluginNotFoundException("could not load plugin", ioe1);
		} catch (ClassNotFoundException cnfe1) {
			Logger.error(this, "could not find plugin class", cnfe1);
			throw new PluginNotFoundException("could not find plugin class", cnfe1);
		} catch (InstantiationException ie1) {
			Logger.error(this, "could not instantiate plugin", ie1);
			throw new PluginNotFoundException("could not instantiate plugin", ie1);
		} catch (IllegalAccessException iae1) {
			Logger.error(this, "could not access plugin main class", iae1);
			throw new PluginNotFoundException("could not access plugin main class", iae1);
		}
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
		/* @Override */
		public String toString() {
			if (this == DOWNLOADING) {
				return "downloading";
			} else if (this == STARTING) {
				return "starting";
			}
			return "PluginProgress[name=" + name + ",startingTime=" + startingTime + ",progress=" + pluginProgress + "]";
		}

	}

}
