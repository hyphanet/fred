/*
  PluginManager.java / Freenet
  Copyright (C) 2004,2005 Change.Tv, Inc
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.jar.JarFile;

import freenet.clients.http.HTTPRequest;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringArrCallback;
import freenet.config.StringArrOption;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.Logger;

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
	private PluginRespirator pluginRespirator = null;
	private final Node node;
	private final NodeClientCore core;
	SubConfig pmconfig;
	
	
	public PluginManager(Node node) {
		pluginInfo = new HashMap();
		toadletList = new HashMap();
		this.node = node;
		this.core = node.clientCore;
		pluginRespirator = new PluginRespirator(node, this);
		
		pmconfig = new SubConfig("pluginmanager", node.config);
		// Start plugins in the config
		pmconfig.register("loadplugin", null, 9, true, false, "Plugins to load on startup ", "Classpath, name and location for plugins to load when node starts up", 
        		new StringArrCallback() {
					public String get() {
						return getConfigLoadString();
					}
					public void set(String val) throws InvalidConfigValueException {
						//if(storeDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Cannot set the plugins that's loaded.");
					}
        });
		
		String fns[] = pmconfig.getStringArr("loadplugin");
		if (fns != null)
			for (int i = 0 ; i < fns.length ; i++) {
				//System.err.println("Load: " + StringArrOption.decode(fns[i]));
				startPlugin(StringArrOption.decode(fns[i]), false);
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
	
	private String getConfigLoadString() {
		StringBuffer out = new StringBuffer();
		try{
			Iterator it = getPlugins().iterator();

			if (it.hasNext())
				out.append(StringArrOption.encode(((PluginInfoWrapper)it.next()).getFilename()));
			while (it.hasNext())
				out.append(StringArrOption.delimiter + StringArrOption.encode(((PluginInfoWrapper)it.next()).getFilename()));
		}catch (NullPointerException e){
			Logger.error(this, "error while loading plugins: disabling them:"+e);
			return "";
		}
		return out.toString();
	}
	
	public void startPlugin(String filename, boolean store) {
		if (filename.trim().length() == 0)
			return;
		Logger.normal(this, "Loading plugin: " + filename);
		FredPlugin plug;
		try {
			plug = LoadPlugin(filename);
			PluginInfoWrapper pi = PluginHandler.startPlugin(this, filename, plug, pluginRespirator);
			// handles FProxy? If so, register
			
			if (pi.isPproxyPlugin())
				registerToadlet(plug);

			if(pi.isIPDetectorPlugin()) {
				node.ipDetector.registerIPDetectorPlugin((FredPluginIPDetector) plug);
			}
			
			synchronized (pluginInfo) {
				pluginInfo.put(pi.getThreadName(), pi);
			}
			Logger.normal(this, "Plugin loaded: " + filename);
		} catch (PluginNotFoundException e) {
			Logger.normal(this, "Loading plugin failed (" + filename + ")", e);
		} catch (UnsupportedClassVersionError e) {
			Logger.error(this, "Could not load plugin "+filename+" : "+e, e);
			System.err.println("Could not load plugin "+filename+" : "+e);
			e.printStackTrace();
			String jvmVersion = System.getProperty("java.vm.version");
			if(jvmVersion.startsWith("1.4.") || jvmVersion.equals("1.4")) {
				System.err.println("Plugin "+filename+" appears to require a later JVM");
				Logger.error(this, "Plugin "+filename+" appears to require a later JVM");
				core.alerts.register(new SimpleUserAlert(true, "Later JVM required by plugin "+filename,
						"The plugin "+filename+" seems to require a later JVM. Please install at least Sun java 1.5, or remove the plugin.",
						UserAlert.ERROR));
			}
		}
		if(store) core.storeConfig();
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
			while (it.hasNext() && (removeKey == null)) {
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
							Logger.error(this, "removing Plugin", ex);
						}
					}
				}
			}
			
			if (removeKey != null)
				pluginInfo.remove(removeKey);
		}
		if(removeKey != null)
			core.storeConfig();
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
							" => /plugins/"+pi.getPluginClassName()+"/");
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
							" => /plugins/"+pi.getPluginClassName()+"/");
				}
			} catch (Throwable ex) {
				Logger.error(this, "removing Toadlet-link: " + rm, ex);
			}
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
		
		// no plugin found
		PluginHTTPException t = new PluginHTTPException();
		t.setReply("Plugin not found: " + plugin);
		throw t;
	}
	
	public void killPlugin(String name) {
		PluginInfoWrapper pi = null;
		boolean found = false;
		synchronized (pluginInfo) {
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext() && !found) {
				pi = (PluginInfoWrapper) pluginInfo.get(it.next());
				if (pi.getThreadName().equals(name))
					found = true;
			}
		}
		if (found)
			if (pi.isThreadlessPlugin())
				removePlugin(pi.getThread());
			else
				pi.stopPlugin();
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
        	for (int tries = 0 ; (tries <= 5) && (cls == null) ; tries++)
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
        			if (tries >= 5)
        				throw new PluginNotFoundException("Initialization error:"
        						+ filename, e);
        			
        			try {
        				Thread.sleep(100);
        			} catch (Exception ee) {}
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
