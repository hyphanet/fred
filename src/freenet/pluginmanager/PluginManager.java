package freenet.pluginmanager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import sun.net.www.protocol.jar.URLJarFile;

import freenet.client.HighLevelSimpleClient;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.Buffer;
import freenet.support.Logger;
import freenet.support.BucketTools.BucketFactoryWrapper;

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
	private Node node;
	
	public PluginManager(Node node) {
		pluginInfo = new HashMap();
		toadletList = new HashMap();
		
		pluginRespirator = new PluginRespirator(node, this);
		
		/*
		SubConfig plugConfig = new SubConfig("pluginmanager", node.config);
		// Start plugins in the config
		plugConfig.register("loadplugin", null, 9, true, "Plugins  load on startup ", "Classpath, name and location for plugins to load when node starts up", 
        		new StringCallback() {
					public String get() {
						return storeDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(storeDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving datastore on the fly not supported at present");
					}
        });
        */
	}
	
	
	
	public void startPlugin(String filename) {
		FredPlugin plug;
		try {
			plug = LoadPlugin(filename);
			PluginInfoWrapper pi = PluginHandler.startPlugin(this, plug, pluginRespirator);
			// handles fproxy? If so, register
			
			if (pi.isPproxyPlugin())
				registerToadlet(plug);
			
			synchronized (pluginInfo) {
				pluginInfo.put(pi.getThreadName(), pi);
			}
		} catch (PluginNotFoundException e) {
			e.printStackTrace();
		}
	}
	
	private void registerToadlet(FredPlugin pl){
		//toadletList.put(e.getStackTrace()[1].getClass().toString(), pl);
		synchronized (toadletList) {
			toadletList.put(pl.getClass().getName(), pl);
		}
		Logger.normal(this, "Added HTTP handler for /plugins/"+pl.getClass().getName()+"/");
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
							Logger.normal(this, "Removed HTTP handler for /plugins/"+
									pi.getPluginClassName()+"/");
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
	
	public Set getPlugins() {
		
		HashSet out = new HashSet();
		synchronized (pluginInfo) {
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(it.next());
				out.add(pi);
			}
		}
		return out;
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
		PluginHTTPException t = new PluginHTTPException();
		t.setReply("Plugin not found: " + plugin);
		throw t;
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
        	"@http://downloads.freenetproject.org/alpha/plugins/" + 
        	filename.substring(filename.lastIndexOf(".")+1, filename.length()-1) +
        	".jar.url";
        	//System.out.println(filename);
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
            	
         
            	// Handle automatic fetching of pluginclassname
            	if (realClass.equals("*")) {
            		if (realURL.startsWith("file:")) {
            			URI liburi = new File(realURL.substring("file:".length())).toURI();
            			realURL = liburi.toString();
            		}
            		
            		URL url = new URL("jar:"+realURL+"!/");
            		JarURLConnection jarConnection = (JarURLConnection)url.openConnection();
            		JarFile jf = jarConnection.getJarFile();
            		//URLJarFile jf = new URLJarFile(new File(liburi));
            		//is = jf.getInputStream(jf.getJarEntry("META-INF/MANIFEST.MF"));
            		
            		//BufferedReader manifest = new BufferedReader(new InputStreamReader(cl.getResourceAsStream("/META-INF/MANIFEST.MF")));
            		
            		//URL url = new URL(parts[1]);
                    //URLConnection uc = cl.getResource("/META-INF/MANIFEST.MF").openConnection();
                	
            		InputStream is = jf.getInputStream(jf.getJarEntry("META-INF/MANIFEST.MF"));
            		BufferedReader in = new BufferedReader(new InputStreamReader(is));	
            		String line;
            		while ((line = in.readLine())!=null) {
        			//	System.err.println(line + "\t\t\t" + realClass);
            			if (line.startsWith("Plugin-Main-Class: ")) {
            				realClass = line.substring("Plugin-Main-Class: ".length()).trim();
            			}
            		}
            		//System.err.println("Real classname: " + realClass);
            	}
            	
				
            	
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
