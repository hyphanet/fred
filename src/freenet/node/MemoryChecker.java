/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.Logger;
import freenet.support.SizeUtil;

public class MemoryChecker implements Runnable {
	private boolean goon = false;
	private final PacketSender ps;
	private int aggressiveGCModificator;
	
	public MemoryChecker(PacketSender ps, int modificator){
		this.ps = ps;
		this.aggressiveGCModificator = modificator;
	}

	protected synchronized void terminate() {
		goon = false;
		Logger.normal(this, "Terminating Memory Checker!");
	}
	
	public boolean isRunning() {
		return goon;
	}
	
	public synchronized void start() {
		goon = true;
		Logger.normal(this, "Starting Memory Checker!");
		run();
	}

	public void run() {
		freenet.support.OSThread.logPID(this);
		if(!goon){
			Logger.normal(this, "Goon is false ; killing MemoryChecker");
			return;
		}
		
		Runtime r = Runtime.getRuntime();
		
		Logger.normal(this, "Memory in use: "+SizeUtil.formatSize((r.totalMemory()-r.freeMemory())));
		
		int sleeptime = aggressiveGCModificator;
		if(sleeptime <= 0) { // We are done
			ps.queueTimedJob(this, 120 * 250); // 30 sec
			return;
		} else
			ps.queueTimedJob(this, 120 * sleeptime);
		
		// FIXME
		// Do not remove until all known memory issues fixed,
		// Especially #66
		// This probably reduces performance, but it makes
		// memory usage *more predictable*. This will make
		// tracking down the sort of nasty unpredictable OOMs
		// we are getting much easier. 
		if(aggressiveGCModificator > 0) {
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			long beforeGCUsedMemory = (r.totalMemory()-r.freeMemory());
			if(logMINOR) Logger.minor(this, "Memory in use before GC: "+beforeGCUsedMemory);
			long beforeGCTime = System.currentTimeMillis();
			System.gc();
			System.runFinalization();
			long afterGCTime = System.currentTimeMillis();
			long afterGCUsedMemory = (r.totalMemory()-r.freeMemory());
			if(logMINOR) {
				Logger.minor(this, "Memory in use after GC: "+afterGCUsedMemory);
				Logger.minor(this, "GC completed after "+(afterGCTime - beforeGCTime)+"ms and \"recovered\" "+(beforeGCUsedMemory - afterGCUsedMemory)+" bytes, leaving "+afterGCUsedMemory+" bytes used");
			}
		}

	}
}