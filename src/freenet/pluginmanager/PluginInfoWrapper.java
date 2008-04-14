package freenet.pluginmanager;

import java.util.Date;
import java.util.HashSet;

import freenet.support.Logger;
import freenet.support.StringArray;

public class PluginInfoWrapper {
	// Parameters to make the object OTP
	private boolean fedPluginThread = false;
	// Public since only PluginHandler will know about it
	private String className;
	private Thread thread;
	private long start;
	private String threadName;
	final FredPlugin plug;
	private boolean isPproxyPlugin;
	private boolean isThreadlessPlugin;
	private boolean isIPDetectorPlugin;
	private boolean isPortForwardPlugin;
	private boolean isMultiplePlugin;
	private boolean isFCPPlugin;
	private String filename;
	private HashSet toadletLinks=new HashSet();
	private boolean stopping = false;
	private boolean unregistered = false;
	//public String 
	
	public PluginInfoWrapper(FredPlugin plug, String filename) {
		this.plug = plug;
		if (fedPluginThread) return;
		className = plug.getClass().toString();
		this.filename = filename;
		threadName = 'p' + className.replaceAll("^class ", "") + '_' + hashCode();
		start = System.currentTimeMillis();
		fedPluginThread = true;
		isPproxyPlugin = (plug instanceof FredPluginHTTP);
		isThreadlessPlugin = (plug instanceof FredPluginThreadless);
		isIPDetectorPlugin = (plug instanceof FredPluginIPDetector);
		isPortForwardPlugin = (plug instanceof FredPluginPortForward);
		isMultiplePlugin = (plug instanceof FredPluginMultiple);
		isFCPPlugin = (plug instanceof FredPluginFCP);
	}

	void setThread(Thread ps) {
		if(thread != null)
			throw new IllegalStateException("Already set a thread");
		thread = ps;
		thread.setName(threadName);
	}
	
	public String toString() {
		return "ID: \"" +threadName + "\", Name: "+ className +", Started: " + (new Date(start)).toString();
	}
	
	public String getThreadName() {
		return threadName;
	}
	
	public long getStarted() {
		return start;
	}
	
	public String getPluginClassName(){
		return plug.getClass().getName();
	}
	
	public synchronized String[] getPluginToadletSymlinks(){
		return StringArray.toArray(toadletLinks.toArray());
	}
	
	public synchronized boolean addPluginToadletSymlink(String linkfrom){
		if (toadletLinks.size() < 1)
			toadletLinks = new HashSet();
		return toadletLinks.add(linkfrom);
	}
	
	public synchronized boolean removePluginToadletSymlink(String linkfrom){
		if (toadletLinks.size() < 1)
			return false;
		return toadletLinks.remove(linkfrom);
	}
	
	/**
	 * Tell the plugin to quit. Interrupt it if it's a thread-based plugin which
	 * might be sleeping. Then call removePlugin() on it on the manager - either
	 * now, if it's threadless, or after it terminates, if it's thread based.
	 * @param manager The plugin manager object.
	 * @param maxWaitTime If a plugin is thread-based, we can wait for it to
	 * terminate. Set to -1 if you don't want to wait at all, 0 to wait forever
	 * or else a value in milliseconds.
	 **/
	public void stopPlugin(PluginManager manager, int maxWaitTime) {
		unregister(manager);
		plug.terminate();
		synchronized(this) {
			stopping = true;
		}
		if(thread != null) {
			thread.interrupt();
			// Will be removed when the thread exits.
			if(maxWaitTime >= 0) {
				try {
					thread.join(maxWaitTime);
				} catch (InterruptedException e) {
					Logger.normal(this, "stopPlugin interrupted while join()ed to terminating plugin thread - maybe one plugin stopping another???");
				}
				if(thread.isAlive()) {
					String error = "Waited for "+thread+" for "+plug+" to exit for "+maxWaitTime+"ms, and it is still alive!";
					Logger.error(this, error);
					System.err.println(error);
					// Dump the thread? Would require post-1.4 features...
				}
			}
		}
		// always remove plugin
		manager.removePlugin(this);
	}
	
	/**
	 * Unregister the plugin from any user interface or other callbacks it may be
	 * registered with. Call this before manager.removePlugin(): the plugin becomes
	 * unvisitable immediately, but it may take time for it to shut down completely.
	 */
	void unregister(PluginManager manager) {
		synchronized(this) {
			if(unregistered) return;
			unregistered = true;
		}
		manager.unregisterPluginToadlet(this);
		if(isIPDetectorPlugin)
			manager.node.ipDetector.unregisterIPDetectorPlugin((FredPluginIPDetector)plug);
		if(isPortForwardPlugin)
			manager.node.ipDetector.unregisterPortForwardPlugin((FredPluginPortForward)plug);
	}

	public boolean isPproxyPlugin() {
		return isPproxyPlugin;
	}

	public String getFilename() {
		return filename;
	}

	public boolean isThreadlessPlugin() {
		return isThreadlessPlugin;
	}

	public boolean isIPDetectorPlugin() {
		return isIPDetectorPlugin;
	}
	
	public boolean isPortForwardPlugin() {
		return isPortForwardPlugin;
	}

	public boolean isMultiplePlugin() {
		return isMultiplePlugin;
	}
	
	public boolean isFCPPlugin() {
		return isFCPPlugin;
	}

	public synchronized boolean isStopping() {
		return stopping;
	}
}
