package freenet.pluginmanager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
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
	
	private HashMap toadletList;
	private HashMap pluginInfo;
	private PluginManager pluginManager = null;
	private PluginRespirator pluginRespirator = null;
	
	public PluginManager(PluginRespirator pluginRespirator) {
		pluginInfo = new HashMap();
		toadletList = new HashMap();
		
		this.pluginRespirator = pluginRespirator;
		pluginRespirator.setPluginManager(this);
		//StartPlugin("misc@file:plugin.jar");
		
		// Needed to include plugin in jar-files
		/*
		 if (new Date().equals(null)){
			System.err.println(new TestPlugin());
		}
		*/
	}
	
	public void startPlugin(String filename) {
		FredPlugin plug;
		try {
			plug = LoadPlugin(filename);
			PluginInfoWrapper pi = PluginHandler.startPlugin(this, plug, pluginRespirator);
			synchronized (pluginInfo) {
				pluginInfo.put(pi.getThreadName(), pi);
			}
		} catch (PluginNotFoundException e) {
			e.printStackTrace();
		}
	}
	
	public void registerToadlet(FredPlugin pl){
		Exception e = new Exception();
		//toadletList.put(e.getStackTrace()[1].getClass().toString(), pl);
		synchronized (toadletList) {
			toadletList.put(pl.getClass().getName(), pl);
		}
		System.err.println("Added HTTP handler for /plugins/"+pl.getClass().getName()+"/");
	}
	
	public void removePlugin(Thread t) {
		Object removeKey = null;
		synchronized (pluginInfo) {
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext() && removeKey == null) {
				Object key = it.next();
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(key);
				if (pi.sameThread(t)) {
					removeKey = key;
					synchronized (toadletList) {
						try {
							toadletList.remove(pi.getPluginClassName());
						} catch (Throwable ex) {
						}
					}
				}
			}
			
			if (removeKey != null)
				pluginInfo.remove(removeKey);
		}
	}

	public String dumpPlugins() {
		StringBuffer out= new StringBuffer();
		synchronized (pluginInfo) {
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(it.next());
				out.append(pi.toString());
				out.append('\n');
			}
		}
		return out.toString();
	}
	
	public String handleHTTPGet(String plugin, String path) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized (toadletList) {
			handler = (FredPlugin)toadletList.get(plugin);
		}
		/*if (handler == null)
			return null;
			*/
		
		if (handler instanceof FredPluginHTTP)
			return ((FredPluginHTTP)handler).handleHTTPGet(path);
		
		// no plugin found
		throw new PluginHTTPException();
	}
	
	public void killPlugin(String name) {
		synchronized (pluginInfo) {
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(it.next());
				if (pi.getThreadName().equals(name))
				{
					pi.stopPlugin();
				}
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
        if (filename.endsWith("*")) {
        	filename = filename.substring(0,filename.length()-1) + 
        	"<http://downloads.freenetproject.org/alpha/plugins/" + 
        	filename.substring(filename.lastIndexOf(".")+1, filename.length()-1) +
        	".jar.url";
        	System.out.println(filename);
        }
        
        if ((filename.indexOf("@") >= 0)) {
            // Open from external file
            try {
            	String realURL = null;
            	String realClass = null;
            	
            	// Load the jar-file
            	String[] parts = filename.split("@");
            	if (parts.length != 2) {
            		throw new PluginNotFoundException("Could not split at \"@\".");
            	}
            	realClass = parts[0];
            	realURL = parts[1];
            	
            	if (filename.endsWith(".url")) {
                		// Load the txt-file
                		BufferedReader in;
                		URL url = new URL(parts[1]);
                        URLConnection uc = url.openConnection();
                    	in = new BufferedReader(
                    			new InputStreamReader(uc.getInputStream()));
                    	
                    	realURL = in.readLine().trim();
            	}
            	
            	// Load the class inside file
            	URL[] serverURLs = new URL[]{new URL(realURL)};
            	ClassLoader cl = new URLClassLoader(serverURLs);
            	cls = cl.loadClass(realClass);
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
