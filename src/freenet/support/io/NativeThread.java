/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support.io;

import java.util.ArrayList;

import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringOption;
import freenet.config.SubConfig;
import freenet.node.NodeStarter;
import freenet.node.NodeStats;
import freenet.support.LibraryLoader;
import freenet.support.Logger;
import freenet.support.api.StringCallback;

/**
 * Do *NOT* forget to call super.run() if you extend it!
 * 
 * @see http://archives.freenetproject.org/thread/20080214.235159.6deed539.en.html
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class NativeThread extends Thread {
	public static final boolean _loadNative;
	private static boolean _disabled;
	public static final int JAVA_PRIORITY_RANGE = Thread.MAX_PRIORITY - Thread.MIN_PRIORITY;
	private final static int NATIVE_PRIORITY_BASE;
	public final static int NATIVE_PRIORITY_RANGE;
	private int currentPriority = Thread.MAX_PRIORITY;
	private boolean dontCheckRenice = false;

	public final static boolean HAS_THREE_NICE_LEVELS;
	public final static boolean HAS_ENOUGH_NICE_LEVELS;
	public final static boolean HAS_PLENTY_NICE_LEVELS;

	
	// TODO: Wire in.
	public static enum PriorityLevel {
		MIN_PRIORITY(1),
		LOW_PRIORITY(3),
		NORM_PRIORITY(5),
		HIGH_PRIORITY(7),
		MAX_PRIORITY(10);
		
		public final int value;
		
		PriorityLevel(int myValue) {
			value = myValue;
		}
		
		public static PriorityLevel fromValue(int value) {
			for(PriorityLevel level :PriorityLevel.values()) {
				if(level.value == value)
					return level;
			}
			
			throw new IllegalArgumentException();
		}
	}
	
	

	public static final int ENOUGH_NICE_LEVELS = PriorityLevel.values().length;
	public static final int MIN_PRIORITY = PriorityLevel.MIN_PRIORITY.value;
	public static final int LOW_PRIORITY = PriorityLevel.LOW_PRIORITY.value;
	public static final int NORM_PRIORITY = PriorityLevel.NORM_PRIORITY.value;
	public static final int HIGH_PRIORITY = PriorityLevel.HIGH_PRIORITY.value;
	public static final int MAX_PRIORITY = PriorityLevel.MAX_PRIORITY.value;
	
	

	static {
		Logger.minor(NativeThread.class, "Running init()");
		// Loading the NativeThread library isn't useful on macos
		boolean maybeLoadNative = ("Linux".equalsIgnoreCase(System.getProperty("os.name"))) && (NodeStarter.extBuildNumber > 18);
		Logger.debug(NativeThread.class, "Run init(): should loadNative="+maybeLoadNative);
		if(maybeLoadNative && LibraryLoader.loadNative("/freenet/support/io/", "NativeThread")) {
			NATIVE_PRIORITY_BASE = getLinuxPriority();
			NATIVE_PRIORITY_RANGE = 20 - NATIVE_PRIORITY_BASE;
			System.out.println("Using the NativeThread implementation (base nice level is "+NATIVE_PRIORITY_BASE+')');
			// they are 3 main prio levels
			HAS_THREE_NICE_LEVELS = NATIVE_PRIORITY_RANGE >= 3;
			HAS_ENOUGH_NICE_LEVELS = NATIVE_PRIORITY_RANGE >= ENOUGH_NICE_LEVELS;
			HAS_PLENTY_NICE_LEVELS = NATIVE_PRIORITY_RANGE >=JAVA_PRIORITY_RANGE;
			if(!(HAS_ENOUGH_NICE_LEVELS && HAS_THREE_NICE_LEVELS))
				System.err.println("WARNING!!! The JVM has been niced down to a level which won't allow it to schedule threads properly! LOWER THE NICE LEVEL!!");
			_loadNative = true;
		} else {
			// unused anyway
			NATIVE_PRIORITY_BASE = 0;
			NATIVE_PRIORITY_RANGE = 19;
			HAS_THREE_NICE_LEVELS = true;
			HAS_ENOUGH_NICE_LEVELS = true;
			HAS_PLENTY_NICE_LEVELS = true;
			_loadNative = false;
		}
		Logger.minor(NativeThread.class, "Run init(): _loadNative = "+_loadNative);
	}
	
	/**
	 * FIXME: This needs to be wired in, will be very useful to be able to configure thread priorities 
	 * FIXME: It still has the following issues:
	 * 
	 * [15:36] <nextgens> where you end up with a +import freenet.node.NodeStats;
	 * [15:36] <nextgens> in  Txt  src/freenet/support/io/NativeThread.java
	 * [15:36] <nextgens> that's utterly wrong.
	 * [15:39] <p0s> nextgens: i didnt find the proper place to get a list of all running threads from, i only found nodestats... what is the proper place?
	 * [15:39] <nextgens> we didn't track those threads
	 * [15:39] <nextgens> because native threads are not all threads
	 * [15:39] <nextgens> pulling them from the stats we make is a bad idea in any case
	 * [15:40] <nextgens> and the way you do it (without any care for synchronization or consistency) is wrong
	 * [15:40] <nextgens> your overriden set() will miss some threads
	 * [15:40] <nextgens> that'd be my first argument against it
	 * [15:40] <nextgens> keep a list in the class itself
	 * [15:41] <p0s> nextgens: ok
	 * [15:41] <nextgens> don't mix up what we aggregate for stats and that
	 * [15:41] <nextgens> and ensure you don't ruin performances altogether because of locking
	 * [15:41] <nextgens> and also ensure that you guarentee consistency
	 * [15:41] <nextgens> and don't forget to set some thread priorities, like your current patch does
	 * [15:42] <nextgens> let me tell you that none of the above is simple on a "hot codepath" like that one
	 * [15:42] <p0s> nextgens: some = which ones? the ones lost due to the lack of locking?
	 * [15:42] <nextgens> and that's partially why we didn't do it
	 * [15:42] <nextgens> yes, the ones due to the lack of locking
	 * [15:43] <nextgens> the original point was: this is a pard of fred with a huge breakage potential
	 * [15:43] <nextgens> don't mess with it unless you have to
	 * [15:43] <nextgens> so please, work on a branch
	 * [15:43] <nextgens> and test it thoroughly
	 * [15:43] <nextgens> on all architectures
	 * [15:43] <nextgens> it does involve JNI
	 * [15:44] <nextgens> and you'll find that you can't change priorities on all threads
	 * [15:44] <nextgens> and in all directions
	 * [15:44] <nextgens> a thread with a high level of nice can't revert back to a higher one iirc
	 * [15:44] <nextgens> on *nix
	 * [15:44] <nextgens> and you'll have other related but different problems on windows
	 * [15:44] <nextgens> but you'll discover that soon enough
	 * 
	 * A config entry for the priority of all threads with a given normalized name.
	 * When a NativeThread is created, it creates a ThreadPriorityCallback for itself and calls loadConfigAndMaybeRegister.
	 *  
	 * 
	 * If another thread already registered a ThreadPriorityCallback, the new one won't be registered.
	 * When the user changes the value of an existing ThreadPriorityCallback, it will adjust the thread priority
	 * of all existing threads with its normalized name. Therefore, only one ThreadPriorityCallback for each
	 * normalized name is needed to be registered, not for each thread.
	 *
	 * @author xor (xor@freenetproject.org)
	 */
	private static class ThreadPriorityCallback extends StringCallback implements EnumerableOptionCallback {
		private final NodeStats nodeStats;
		private final String normalizedThreadName;
		private final PriorityLevel defaultPriority;
		private PriorityLevel currentPriority;
		
		public ThreadPriorityCallback(NodeStats stats, NativeThread thread) {
			nodeStats = stats;
			normalizedThreadName = thread.getNormalizedName();
			defaultPriority = currentPriority = PriorityLevel.fromValue(thread.getPriority());
		}
		
		@Override
		public String get(){
			return currentPriority.toString();
		}
		
		@Override
		public void set(String val) throws InvalidConfigValueException{
			try {
				currentPriority = PriorityLevel.valueOf(val);
				
				for(NativeThread thread : nodeStats.getNativeThreadsByNormalizedName(normalizedThreadName)) {
					thread.setPriority(currentPriority.value);
				}
			} catch(IllegalArgumentException e) {
				throw new InvalidConfigValueException("Invalid priority level");
			}
		}
		
		public String[] getPossibleValues() {
			ArrayList<String> values = new ArrayList<String>(PriorityLevel.values().length + 1);
			for(PriorityLevel level : PriorityLevel.values()) {
				values.add(level.toString());
			}
			return (String[])values.toArray();
		}
		
		private String getThreadNameForConfig() {
			String name = normalizedThreadName;
			name = name.replaceAll("^[a-zA-Z]", "_");
			return name;
		}
		
		public void loadConfigAndMaybeRegister(SubConfig config, NativeThread thread) {
			final String configThreadName = getThreadNameForConfig();
			final String configOptionName = configThreadName + "_priority";
			
			final StringOption existingOption = (StringOption)config.getOption(configOptionName);
			
			if(existingOption != null) {
				try {
					currentPriority = PriorityLevel.valueOf(existingOption.getValue());
					thread.setPriority(currentPriority.value);
				} catch(Exception e) {
					Logger.error(this, "Loading thread priority config failed", e);
				}
			}
			
			final ThreadPriorityCallback existingCallback = existingOption==null ? null : (ThreadPriorityCallback)existingOption.getCallback();
			
			if(existingCallback == null) {
				try {
					config.register(configOptionName, 
							defaultPriority.toString(), // default value 
							configThreadName.hashCode(), // sort order
							true, // expert only
							false, // force write
							"NativeThread.PrioritiesShortDesc." + configThreadName, // l10n description
							"NativeThread.PrioritiesLongDesc." + configThreadName,
							this);
				} catch(Exception e) {
					Logger.error(this, "Registration failed", e);
				}
			} else {
				// We interpret the initial priority which was passed in when creating a NativeThread as the default priority. 
				// This is not obvious from the constructors of NativeThreads.
				// While implementing this I assumed that the users of the NativeThread class are very likely to always use the same priority 
				// for a given normalized name. If the priorities vary among thread names, we hereby alert the user that 
				if(!defaultPriority.equals(existingCallback.defaultPriority))
					Logger.error(this, "Default priority for this normalized thread name is ambiguous, please use different names: " + normalizedThreadName);
			}

		}
	}
	
	public NativeThread(String name, int priority, boolean dontCheckRenice) {
		super(name);
		this.currentPriority = priority;
		this.dontCheckRenice = dontCheckRenice;
	}
	
	public NativeThread(Runnable r, String name, int priority, boolean dontCheckRenice) {
		super(r, name);
		this.currentPriority = priority;
		this.dontCheckRenice = dontCheckRenice;
	}
	
	public NativeThread(ThreadGroup g, Runnable r, String name, int priority, boolean dontCheckRenice) {
		super(g, r, name);
		this.currentPriority = priority;
		this.dontCheckRenice = dontCheckRenice;
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
	
	@Override
	public final void run() {
		if(!setNativePriority(currentPriority))
			System.err.println("setNativePriority("+currentPriority+") has failed!");
		super.run();
		realRun();
	}
	
	public void realRun() {
		// Override this for convenience when doing new NativeThread() { ... }
	}
	
	/**
	 * Rescale java priority and set linux priority.
	 */
	private boolean setNativePriority(int prio) {
		Logger.minor(this, "setNativePriority("+prio+")");
		setPriority(prio);
		if(!_loadNative) {
			Logger.minor(this, "_loadNative is false");
			return true;
		}
		int realPrio = getLinuxPriority();
		if(_disabled) {
			Logger.normal(this, "Not setting native priority as disabled due to renicing");
			return false;
		}
		if(NATIVE_PRIORITY_BASE != realPrio && !dontCheckRenice) {
			/* The user has reniced freenet or we didn't use the PacketSender to create the thread
			 * either ways it's bad for us.
			 * 
			 * Let's disable the renicing as we can't rely on it anymore.
			 */
			_disabled = true;
			Logger.error(this, "Freenet has detected it has been reniced : THAT'S BAD, DON'T DO IT! Nice level detected statically: "+NATIVE_PRIORITY_BASE+" actual nice level: "+realPrio+" on "+this);
			System.err.println("Freenet has detected it has been reniced : THAT'S BAD, DON'T DO IT! Nice level detected statically: "+NATIVE_PRIORITY_BASE+" actual nice level: "+realPrio+" on "+this);
			new NullPointerException().printStackTrace();
			return false;
		}
		final int linuxPriority = NATIVE_PRIORITY_BASE + NATIVE_PRIORITY_RANGE - (NATIVE_PRIORITY_RANGE * (prio - MIN_PRIORITY)) / JAVA_PRIORITY_RANGE;
		if(linuxPriority == realPrio) return true; // Ok
		// That's an obvious coding mistake
		if(prio < currentPriority)
			throw new IllegalStateException("You're trying to set a thread priority" +
				" above the current value!! It's not possible if you aren't root" +
				" and shouldn't ever occur in our code. (asked="+prio+':'+linuxPriority+" currentMax="+
				+currentPriority+':'+NATIVE_PRIORITY_BASE+") SHOUDLN'T HAPPEN, please report!");
		Logger.minor(this, "Setting native priority to "+linuxPriority+" (base="+NATIVE_PRIORITY_BASE+") for "+this);
		return setLinuxPriority(linuxPriority);
	}
	
	public int getNativePriority() {
		return currentPriority;
	}

	public static boolean usingNativeCode() {
		return _loadNative && !_disabled;
	}
	
	public static String normalizeName(String name) {
		if(name.indexOf(" for ") != -1)
			name = name.substring(0, name.indexOf(" for "));
		if(name.indexOf("@") != -1)
			name = name.substring(0, name.indexOf("@"));
		if (name.indexOf("(") != -1)
			name = name.substring(0, name.indexOf("("));
		
		return name;
	}
	
	public String getNormalizedName() {
		return normalizeName(getName());
	}
}
