package freenet.pluginmanager;

import java.util.Date;
import java.util.HashSet;

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
	private String filename;
	private HashSet toadletLinks=new HashSet(); 
	//public String 
	
	public PluginInfoWrapper(FredPlugin plug, Thread ps, String filename) {
		if (fedPluginThread) return;
		className = plug.getClass().toString();
		thread = ps;
		this.plug = plug;
		this.filename = filename;
		threadName = "p" + className.replaceAll("^class ", "") + "_" + ps.hashCode();
		start = System.currentTimeMillis();
		ps.setName(threadName);
		fedPluginThread = true;
		isPproxyPlugin = (plug instanceof FredPluginHTTP);
		isThreadlessPlugin = (plug instanceof FredPluginThreadless);
		isIPDetectorPlugin = (plug instanceof FredPluginIPDetector);
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
			return (String[])toadletLinks.toArray();
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
	
	public boolean sameThread(Thread t){
		return (t == thread);
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
	
	public Thread getThread() {
		return thread;
	}
	
}
