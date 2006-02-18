package pluginmanager;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;

import freenet.client.HighLevelSimpleClient;

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
	
	
	private static HashMap pluginInfo;
	private static PluginManager pluginManager = null;
	private PluginRespirator pluginRespirator = null;
	
	public PluginManager(PluginRespirator pluginRespirator) {
		pluginInfo = new HashMap();
		
		this.pluginRespirator = pluginRespirator;
		//StartPlugin("misc@file:plugin.jar");
		
		// Needed to include plugin in jar-files
		if (new Date().equals(null))
			System.err.println(new TestPlugin());
	}
	
	public void startPlugin(String filename) {
		FredPlugin plug;
		try {
			plug = LoadPlugin(filename);
			PluginInfoWrapper pi = PluginHandler.startPlugin(this, plug, pluginRespirator);
			pluginInfo.put(pi.getThreadName(), pi);
		} catch (PluginNotFoundException e) {
			e.printStackTrace();
		}
	}
	
	public void removePlugin(Thread t) {
		Object removeKey = null;
		{
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext()) {
				Object key = it.next();
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(key);
				if (pi.sameThread(t))
					removeKey = key;
			}
		}
		if (removeKey != null)
			pluginInfo.remove(removeKey);
	}

	public void dumpPlugins() {
		Iterator it = pluginInfo.keySet().iterator();
		while (it.hasNext()) {
			PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(it.next());
			System.out.println(pi);
		}
	}
	
	public void killPlugin(String name) {
		Iterator it = pluginInfo.keySet().iterator();
		while (it.hasNext()) {
			PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(it.next());
			if (pi.getThreadName().equals(name))
			{
				pi.stopPlugin();
			}
		}
	}
	
	
	/**
	 * Method to load a plugin from the given path and return is as an object.
	 * Will accept filename to be of one of the following forms:
	 * "classname" to load a class from the current classpath
	 * "classame@file:/path/to/jarfile.jar" to load class from an other jarfile.
	 * 
	 * @param filename 	The filename to load from
	 * @return			An instanciated object of the plugin
	 * @throws PluginNotFoundException	If anything goes wrong.
	 */
	private FredPlugin LoadPlugin(String filename) throws PluginNotFoundException {
        Class cls = null;
        
        if (filename.indexOf("@file:") >= 0) {
            // Open from extern file
            try {
                // Load the jar-file
                String[] parts = filename.split("@file:");
                if (parts.length != 2) {
                	throw new PluginNotFoundException("Could not split at \"@file:\".");
                }
                
                // Load the class inside file
                URL[] serverURLs = new URL[]{new URL("file:" + parts[1])};
                ClassLoader cl = new URLClassLoader(serverURLs);
                cls = cl.loadClass(parts[0]);
            } catch (Exception e) {
                throw new PluginNotFoundException("Initialization error:"
                		+ filename, e);
            }
        } else {
            // Load class
            try {
                cls = Class.forName(filename);
            } catch (ClassNotFoundException e) {
            	throw new PluginNotFoundException(filename);
            }
        }
        
        if(cls == null)
        	throw new PluginNotFoundException("Unknown error");
        
        // Class loaded... Objectize it!
        Object o = null;
        try {
            o = cls.newInstance();
        } catch (Exception e) {
        	throw new PluginNotFoundException("Could not re-create plugin:" +
        			filename, e);
        }
        
        // See if we have the right type
        if (!(o instanceof FredPlugin)) {
        	throw new PluginNotFoundException("Not a plugin: " + filename);
        }
        
        return (FredPlugin)o;
	}
}
