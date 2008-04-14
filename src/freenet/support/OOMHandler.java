/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.Iterator;
import java.util.Set;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.support.Logger;

/**
 * Do this processing as a standard response to an OutOfMemoryError
 */
public class OOMHandler {
	private static volatile boolean isOOM = false;
	
	/**
	 * Emergency memory, freed when OOM occur. Marked <code>volatile</code> to make sure gc thread
	 * see it's free'd.
	 */
	private static volatile byte[] emergencyPool = new byte[8192];
	
	/**
	 * List of {@link OOMHook}s
	 */
	private static Set oomHooks = new WeakHashSet();
	
	public static void addOOMHook(OOMHook hook) {
		synchronized (oomHooks) {
			oomHooks.add(hook);
		}
	}
	
	public static void handleOOM(OutOfMemoryError e) {
		if (isOOM) {
			Logger.error(null, "Double OOM", e);
			return;
		}
		
		isOOM = true;
		
		Runtime r = null;
		try {
			r = Runtime.getRuntime();
			long usedAtStart = r.totalMemory() - r.freeMemory();
			
			if (emergencyPool != null)
				emergencyPool = null;
			
			System.gc();
			System.runFinalization();
			
			// iterate all oom hooks
			Iterator it = oomHooks.iterator();
			while (it.hasNext()) {
				OOMHook hook = ((OOMHook) it.next());
				if (hook != null) {
					try {
						hook.handleOOM();
					} catch (Throwable t) {
						//ignore
					}
				}

				System.gc();
			}
			
			System.gc();
			System.runFinalization();
			
			System.err.println(e.getClass());
			System.err.println(e.getMessage());
			e.printStackTrace();
			if(e.getMessage().equals("Java heap space")) {
				Thread.dumpStack();
			}
			long usedNow = r.totalMemory() - r.freeMemory();
			System.err.println("Memory: GC "+SizeUtil.formatSize(usedAtStart, false)+" -> "+SizeUtil.formatSize(usedNow, false)+": total "+SizeUtil.formatSize(r.totalMemory(), false)+" free "+SizeUtil.formatSize(r.freeMemory(), false)+" max "+SizeUtil.formatSize(r.maxMemory(), false));
			ThreadGroup tg = Thread.currentThread().getThreadGroup();
			while(tg.getParent() != null) tg = tg.getParent();
			System.err.println("Running threads: "+tg.activeCount());
			// Logger after everything else since it might throw
			Logger.error(null, "Caught "+e, e);
			Logger.error(null, "Memory: GC "+SizeUtil.formatSize(usedAtStart, false)+" -> "+SizeUtil.formatSize(usedNow, false)+": total "+SizeUtil.formatSize(r.totalMemory(), false)+" free "+SizeUtil.formatSize(r.freeMemory(), false)+" max "+SizeUtil.formatSize(r.maxMemory(), false));
			Logger.error(null, "Running threads: "+tg.activeCount());
		} catch (Throwable t) {
			// Try without GCing, it might be a thread error; GCing creates a thread
			System.err.println("Caught handling OOM "+e+" : "+t);
			e.printStackTrace();
			if(r != null)
				System.err.println("Memory: total "+SizeUtil.formatSize(r.totalMemory(), false)+" free "+SizeUtil.formatSize(r.freeMemory(), false)+" max "+SizeUtil.formatSize(r.maxMemory(), false));
			ThreadGroup tg = Thread.currentThread().getThreadGroup();
			while(tg.getParent() != null) tg = tg.getParent();
			System.err.println("Running threads: "+tg.activeCount());
			WrapperManager.requestThreadDump(); // Will probably crash, but never mind...
		} finally {
			isOOM = false;
		}
	}
}
