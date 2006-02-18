package pluginmanager;

import java.util.Date;

public class PluginInfoWrapper {
	// Parameters to make the object OTP
	private boolean fedPluginThread = false;
	// Public since only PluginHandler will know about it
	private String className;
	private Thread thread;
	private long start;
	private String threadName;
	private FredPlugin plug;
	//public String 
	
	public void putPluginThread(FredPlugin plug, Thread ps) {
		if (fedPluginThread) return;
		
		className = plug.getClass().toString();
		thread = ps;
		this.plug = plug;
		threadName = "p" + className.replaceAll("^class ", "") + "_" + ps.hashCode();
		start = System.currentTimeMillis();
		ps.setName(threadName);
		
		fedPluginThread = true;
	}
	
	public String toString() {
		return "ID: \"" +threadName + "\", Name: "+ className +" Started: " + (new Date(start)).toString();
	}
	
	public String getThreadName() {
		return threadName;
	}
	
/*	public FredPlugin getPlugin(){
		return plug;
	}
	*/
	public void stopPlugin() {
		plug.terminate();
		//thread.interrupt();
	}
	
	public boolean sameThread(Thread t){
		return (t == thread);
	}
	
}
