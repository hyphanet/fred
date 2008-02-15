/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;

/**
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class NativeThread extends Thread {
	private static boolean _loadNative;
	public static final int JAVA_PRIO_RANGE = MAX_PRIORITY - MIN_PRIORITY;
	private static final int NATIVE_PRIORITY_BASE;
	public static final int NATIVE_PRIORITY_RANGE;
	private int currentPriority = Thread.MAX_PRIORITY;

	public static final boolean HAS_THREE_NICE_LEVELS;
	public static final boolean HAS_ENOUGH_NICE_LEVELS;
	
	static {
		_loadNative = "Linux".equalsIgnoreCase(System.getProperty("os.name"));
		if(_loadNative) {
			//System.loadLibrary("NativeThread");
			loadNative();
			NATIVE_PRIORITY_BASE = getLinuxPriority();
			NATIVE_PRIORITY_RANGE = 19 - NATIVE_PRIORITY_BASE;
			System.out.println("Using the NativeThread implementation (base nice level is "+NATIVE_PRIORITY_BASE+')');
			// they are 3 main prio levels
			HAS_THREE_NICE_LEVELS = NATIVE_PRIORITY_RANGE >= 3;
			HAS_ENOUGH_NICE_LEVELS = NATIVE_PRIORITY_RANGE >=JAVA_PRIO_RANGE;
			if(!(HAS_ENOUGH_NICE_LEVELS && HAS_THREE_NICE_LEVELS))
				System.err.println("WARNING!!! The JVM has been niced down to a level which won't allow it to schedule threads properly! LOWER THE NICE LEVEL!!");
		} else {
			// unused anyway
			NATIVE_PRIORITY_BASE = 0;
			NATIVE_PRIORITY_RANGE = 19;
			HAS_THREE_NICE_LEVELS = true;
			HAS_ENOUGH_NICE_LEVELS = true;
		}
	}
	
	private static void loadNative() {
		// System.loadLibrary("NativeThread");
		String arch;
		if(System.getProperty("os.arch").toLowerCase().matches("(i?[x0-9]86_64|amd64)")) {
			arch = "amd64";
		} else if(System.getProperty("os.arch").toLowerCase().matches("(ppc)")) {
			arch = "ppc";
		} else {
			arch = "i386";
		}
		
		String resourceName = "/freenet/support/io/libNativeThread-" + arch + ".so";
		try {
			System.out.println("ok");
			// Get the resource
			URL resource = NativeThread.class.getResource(resourceName);
			
			// Get input stream from jar resource
			InputStream inputStream = resource.openStream();

			// Copy resource to filesystem in a temp folder with a unique name
			File temporaryLib = File.createTempFile("libNativeThread", ".so");
			
			// Delete on exit the dll
			temporaryLib.deleteOnExit();
			
			FileOutputStream outputStream = new FileOutputStream(temporaryLib);
			byte[] array = new byte[2048];
			int read = 0;
			while((read = inputStream.read(array)) > 0) {
				outputStream.write(array, 0, read);
			}
			outputStream.close();

			// Finally, load the dll
			System.out.println("Attempting to load the NativeThread library ["+resource+']');
			System.load(temporaryLib.getPath());
		} catch(Throwable e) {
			e.printStackTrace();
		}
	}
	
	public NativeThread(String name, int priority) {
		super(name);
		this.currentPriority = priority;
	}
	
	public NativeThread(Runnable r, String name, int priority) {
		super(r, name);
		this.currentPriority = priority;
	}
	
	public NativeThread(ThreadGroup g, Runnable r, String name, int priority) {
		super(g, r, name);
		this.currentPriority = priority;
	}

	/**
	 * Set linux priority (JNI call)
	 * 
	 * @return true if successful, false otherwise.
	 */
	private static native boolean setLinuxPriority(int prio);
	
	/**
	 * Get linux priority (JNI call)
	 */
	private static native int getLinuxPriority();	
	
	public void run() {
		if(!setNativePriority(currentPriority))
			System.err.println("setNativePriority("+currentPriority+") has failed!");
		super.run();
	}
	
	/**
	 * Rescale java priority and set linux priority.
	 */
	private boolean setNativePriority(int prio) {
		setPriority(prio);
		if(!_loadNative) return true;
		if(NATIVE_PRIORITY_BASE != getLinuxPriority()) {
			/* The user has reniced freenet or we didn't use the PacketSender to create the thread
			 * either ways it's bad for us.
			 * 
			 * Let's diable the renicing as we can't rely on it anymore.
			 */
			_loadNative = false;
			System.err.println("Freenet has detected it has been reniced : THAT'S BAD, DON'T DO IT!");
			return false;
		}
		final int linuxPriority = NATIVE_PRIORITY_BASE + NATIVE_PRIORITY_RANGE - (NATIVE_PRIORITY_RANGE * (prio - MIN_PRIORITY)) / JAVA_PRIO_RANGE;
		// That's an obvious coding mistake
		if(prio < currentPriority)
			throw new IllegalStateException("You're trying to set a thread priority" +
				" above the current value!! It's not possible if you aren't root" +
				" and shouldn't ever occur in our code. (asked="+prio+':'+linuxPriority+" currentMax="+
				+currentPriority+':'+NATIVE_PRIORITY_BASE+") SHOUDLN'T HAPPEN, please report!");
		return setLinuxPriority(linuxPriority);
	}
	
	public int getNativePriority() {
		return currentPriority;
	}
}
