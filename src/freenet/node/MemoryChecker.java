package freenet.node;

import freenet.support.Logger;

public class MemoryChecker implements Runnable {
	
	public void run() {
		Runtime r = Runtime.getRuntime();
		while(true) {
			int sleeptime = Node.aggressiveGCModificator;
			if(sleeptime <= 0)
				sleeptime = 250;
			
			for(int i=0;i<120;i++) {
				try {
					Thread.sleep(sleeptime);
				} catch (InterruptedException e) {
					// Ignore
				}
				Logger.minor(this, "Memory in use: "+(r.totalMemory()-r.freeMemory()));
			}
			try {
				Thread.sleep(sleeptime);
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
			if(Node.aggressiveGCModificator > 0){
				Logger.minor(this, "Memory in use before GC: "+(r.totalMemory()-r.freeMemory()));
				System.gc();
				System.runFinalization();
				Logger.minor(this, "Memory in use after GC: "+(r.totalMemory()-r.freeMemory()));
			}
		}
	}
}