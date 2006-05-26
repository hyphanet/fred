package freenet.node;

import freenet.support.Logger;

public class MemoryChecker implements Runnable {
	
	public void run() {
		Runtime r = Runtime.getRuntime();
		while(true) {
			for(int i=0;i<120;i++) {
				try {
					Thread.sleep(250);
				} catch (InterruptedException e) {
					// Ignore
				}
				Logger.minor(this, "Memory in use: "+(r.totalMemory()-r.freeMemory()));
			}
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				// Ignore
			}
			// FIXME
			// Do not remove until all known memory issues fixed,
			// Especially #66
			// This probably reduces performance, but it makes
			// memory usage *more predictable*. This will make
			// tracking down the sort of nasty unpredictable OOMs
			// we are getting much easier. 
			Logger.minor(this, "Memory in use before GC: "+(r.totalMemory()-r.freeMemory()));
			System.gc();
			System.runFinalization();
			Logger.minor(this, "Memory in use after GC: "+(r.totalMemory()-r.freeMemory()));
		}
	}
}