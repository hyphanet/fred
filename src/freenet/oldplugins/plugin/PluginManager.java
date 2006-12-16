/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.oldplugins.plugin;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import freenet.config.InvalidConfigValueException;
import freenet.config.StringArrOption;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.Logger;
import freenet.support.api.StringArrCallback;

/**
 * Manages plugins.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class PluginManager {

	/** Object used for synchronization. */
	private final Object syncObject = new Object();

	/** The node. */
	private final Node node;

	/** The configuration of this plugin manager. */
	private final SubConfig config;

	/** Currently loaded plugins. */
	private List plugins = new ArrayList();

	/**
	 * Creates a new plugin manager.
	 * 
	 * @param node
	 *            The node
	 */
	public PluginManager(Node node) {
		this.node = node;

		config = new SubConfig("pluginmanager2", node.config);
		config.register("loadedPlugins", new String[0], 9, true, true, "Plugins to load on start up", "A list of plugins that are started when the node starts", new StringArrCallback() {

			/**
			 * Returns the current value of this option.
			 * 
			 * @see freenet.support.api.StringArrCallback#get()
			 * @return The current value of this option
			 */
			public String[] get() {
				if(plugins.size() == 0) return new String[0];
				String[] retval;
				synchronized (syncObject) {
					retval = new String[plugins.size()];
					for(int i=0;i<plugins.size();i++) {
						Plugin plugin = (Plugin) plugins.get(i);
						retval[i] = plugin.getClass().getName();
					}
				}
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Plugin list: "+retval);
				return retval;
			};

			/**
			 * Sets the new value of this option.
			 * 
			 * @see freenet.support.api.StringArrCallback#set(java.lang.String)
			 * @param val
			 *            The new value
			 * @throws InvalidConfigValueException
			 *             if setting the value is not allowed, or the new value
			 *             is not valid
			 */
			public void set(String[] val) throws InvalidConfigValueException {
				throw new InvalidConfigValueException("Not supported");
			};
		});

		String[] loadedPluginNames = config.getStringArr("loadedPlugins");
		if (loadedPluginNames != null && loadedPluginNames.length > 0) {
			for (int pluginIndex = 0, pluginCount = loadedPluginNames.length; pluginIndex < pluginCount; pluginIndex++) {
				String pluginName = loadedPluginNames[pluginIndex];
				try {
					addPlugin(pluginName, false);
				} catch (Throwable t) {
					Logger.error(this, "Failed to load plugin "+pluginName+" : "+t, t);
				}
			}
		}

		config.finishedInitialization();
	}

	/**
	 * Returns the node that created this plugin manager.
	 * 
	 * @return The node that created this plugin manager
	 */
	public Node getNode() {
		return node;
	}

	/**
	 * Returns all currently loaded plugins. The array is returned in no
	 * particular order.
	 * 
	 * @return All currently loaded plugins
	 */
	public Plugin[] getPlugins() {
		synchronized (syncObject) {
			return (Plugin[]) plugins.toArray(new Plugin[plugins.size()]);
		}
	}

	/**
	 * Adds a plugin to the plugin manager. The name can contain a URL for a jar
	 * file from which the plugin is then loaded. If it does the URL and the
	 * plugin name are separated by a '@', e.g.
	 * 'plugin.TestPlugin@http://www.example.com/test.jar'. URLs can contain
	 * every protocol your VM understands.
	 * 
	 * @see URL
	 * @param pluginName
	 *            The name of the plugin
	 */
	public void addPlugin(String pluginName, final boolean store) throws IllegalArgumentException {
		if(Logger.shouldLog(Logger.MINOR, this)) 
			Logger.minor(this, "Loading plugin "+pluginName+(store?"" : " (don't store afterwards)"));
		final Plugin newPlugin = createPlugin(pluginName);
		if (newPlugin == null) {
			throw new IllegalArgumentException();
		}
		newPlugin.setPluginManager(this);
		synchronized (syncObject) {
			plugins.add(newPlugin);
		}
		node.ps.queueTimedJob(new Runnable() {
			public void run() {
				try{
					while(!node.isHasStarted())
						Thread.sleep(1000);
				}catch (InterruptedException e) {}
				newPlugin.startPlugin();
				if(store)
					node.clientCore.storeConfig();
			}
		}, 0);
		
	}

	/**
	 * Remoes the plugin from the list of running plugins. The plugin is stopped
	 * before removing it.
	 * 
	 * @param plugin
	 *            The plugin to remove
	 */
	public void removePlugin(Plugin plugin, boolean store) {
		plugin.stopPlugin();
		synchronized (syncObject) {
			plugins.remove(plugin);
		}
		if(store)
			node.clientCore.storeConfig();
	}

	/**
	 * Creates a plugin from a name. The name can contain a URL for a jar file
	 * from which the plugin is then loaded. If it does the URL and the plugin
	 * name are separated by a '@', e.g.
	 * 'plugin.TestPlugin@http://www.example.com/test.jar'. URLs can contain
	 * every protocol your VM understands.
	 * <p>
	 * <b>WARNING:</b> The code to load JAR files from URLs has <b>not</b>
	 * been tested.
	 * 
	 * @see URL
	 * @param pluginName
	 *            The name of the plugin
	 * @return The created plugin, or <code>null</code> if the plugin could
	 *         not be created
	 */
	private Plugin createPlugin(String pluginName) {
		int p = pluginName.indexOf('@');
		String pluginSource = null;
		
		/* split up */
		if (p > -1) {
			pluginSource = pluginName.substring(p + 1);
			pluginName = pluginName.substring(0, p);
		}

		/* load jar file */
		ClassLoader classLoader = getClass().getClassLoader();
		if (pluginSource != null) {
			try {
				URL pluginSourceUrl = new URL(pluginSource);
				classLoader = new URLClassLoader(new URL[] { pluginSourceUrl });
			} catch (MalformedURLException mue1) {
				Logger.normal(this, "could not create class loader", mue1);
				return null;
			}
		}

		/* load class from class loader */
		try {
			Class pluginClass = classLoader.loadClass(pluginName);
			if (Plugin.class.isAssignableFrom(pluginClass)) {
				Plugin plugin = (Plugin) pluginClass.newInstance();
				return plugin;
			}
		} catch (ClassNotFoundException e) {
			Logger.normal(this, "could not find plugin class: " + pluginName+" : "+e, e);
		} catch (InstantiationException e) {
			Logger.normal(this, "could not instantiate plugin class: " + pluginName+" : "+e, e);
		} catch (IllegalAccessException e) {
			Logger.normal(this, "could not instantiate plugin class: " + pluginName+" : "+e, e);
		}
		return null;
	}

	public NodeClientCore getClientCore() {
		return node.clientCore;
	}

}
