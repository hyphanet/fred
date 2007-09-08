package freenet.pluginmanager;

import java.util.Date;
import java.util.HashSet;

import freenet.support.StringArray;

public class PluginInfoWrapper {
	// Parameters to make the object OTP
	private boolean fedPluginThread = false;
	// Public since only PluginHandler will know about it
	private String className;
	private Thread thread;
	private long start;
	private String threadName;
	private FredPlugin plug;
	private boolean isPproxyPlugin;
	private boolean isThreadlessPlugin;
	private boolean isIPDetectorPlugin;
	private boolean isPortForwardPlugin;
	private String filename;
	private HashSet toadletLinks=new HashSet(); 
	//public String 
	
	public PluginInfoWrapper(FredPlugin plug, String filename) {
		if (fedPluginThread) return;
		className = plug.getClass().toString();
		this.plug = plug;
		this.filename = filename;
		threadName = 'p' + className.replaceAll("^class ", "") + '_' + hashCode();
		start = System.currentTimeMillis();
		fedPluginThread = true;
		isPproxyPlugin = (plug instanceof FredPluginHTTP);
		isThreadlessPlugin = (plug instanceof FredPluginThreadless);
		isIPDetectorPlugin = (plug instanceof FredPluginIPDetector);
		isPortForwardPlugin = (plug instanceof FredPluginPortForward);
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
	
	public String[] getPluginToadletSymlinks(){
		synchronized (toadletLinks) {
			return StringArray.toArray(toadletLinks.toArray());
		}
	}
	
	public boolean addPluginToadletSymlink(String linkfrom){
		synchronized (toadletLinks) {
			if (toadletLinks.size() < 1)
				toadletLinks = new HashSet();
			return toadletLinks.add(linkfrom);
		}
	}
	
	public boolean removePluginToadletSymlink(String linkfrom){
		synchronized (toadletLinks) {
			if (toadletLinks.size() < 1)
				return false;
			return toadletLinks.remove(linkfrom);
		}
	}
	
	public void stopPlugin() {
		plug.terminate();
		thread.interrupt();
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
	
}
