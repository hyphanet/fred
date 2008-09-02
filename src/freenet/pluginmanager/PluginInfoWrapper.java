package freenet.pluginmanager;

import java.util.Date;
import java.util.HashSet;

import freenet.l10n.L10n;
import freenet.support.Logger;

public class PluginInfoWrapper {
	// Parameters to make the object OTP
	private boolean fedPluginThread = false;
	// Public since only PluginHandler will know about it
	private final String className;
	private Thread thread;
	private final long start;
	final PluginRespirator pr;
	private final String threadName;
	final FredPlugin plug;
	private final boolean isPproxyPlugin;
	private final boolean isThreadlessPlugin;
	private final boolean isIPDetectorPlugin;
	private final boolean isBandwidthIndicator;
	private final boolean isPortForwardPlugin;
	private final boolean isMultiplePlugin;
	private final boolean isFCPPlugin;
	private final boolean isVersionedPlugin;
	private final String filename;
	private HashSet<String> toadletLinks = new HashSet<String>();
	private volatile boolean stopping = false;
	private volatile boolean unregistered = false;
	private final boolean isThemedPlugin;
	private final boolean isL10nPlugin;
	
	public PluginInfoWrapper(PluginRespirator pr, FredPlugin plug, String filename) {
		this.plug = plug;
		className = plug.getClass().toString();
		this.filename = filename;
		this.pr = pr;
		threadName = 'p' + className.replaceAll("^class ", "") + '_' + hashCode();
		start = System.currentTimeMillis();
		fedPluginThread = true;
		isBandwidthIndicator = (plug instanceof FredPluginBandwidthIndicator);
		isPproxyPlugin = (plug instanceof FredPluginHTTP);
		isThreadlessPlugin = (plug instanceof FredPluginThreadless);
		isIPDetectorPlugin = (plug instanceof FredPluginIPDetector);
		isPortForwardPlugin = (plug instanceof FredPluginPortForward);
		isMultiplePlugin = (plug instanceof FredPluginMultiple);
		isFCPPlugin = (plug instanceof FredPluginFCP);
		isVersionedPlugin = (plug instanceof FredPluginVersioned);
		isThemedPlugin = (plug instanceof FredPluginThemed);
		isL10nPlugin = (plug instanceof FredPluginL10n);
	}

	void setThread(Thread ps) {
		if(thread != null)
			throw new IllegalStateException("Already set a thread");
		thread = ps;
		thread.setName(threadName);
	}
	
	@Override
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
	
	public String getPluginVersion() {
		if (isVersionedPlugin) {
			return ((FredPluginVersioned)plug).getVersion();
		} else {
			return L10n.getString("PproxyToadlet.noVersion");
		}
	}
	
	public synchronized String[] getPluginToadletSymlinks(){
		return toadletLinks.toArray(new String[0]);
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
		if(isBandwidthIndicator)
			manager.node.ipDetector.unregisterBandwidthIndicatorPlugin((FredPluginBandwidthIndicator)plug);
	}

	public boolean isPproxyPlugin() {
		return isPproxyPlugin;
	}

	public String getFilename() {
		return filename;
	}
	
	public boolean isBandwidthIndicator() {
		return isBandwidthIndicator;
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
	
	public boolean isThemedPlugin() {
		return isThemedPlugin;
	}
	public boolean isL10nPlugin() {
		return isL10nPlugin;
	}

	public synchronized boolean isStopping() {
		return stopping;
	}
}
