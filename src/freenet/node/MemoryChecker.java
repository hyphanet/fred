/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.SizeUtil;
import freenet.support.Ticker;
import freenet.support.Logger.LogLevel;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;

public class MemoryChecker implements Runnable {
	private volatile boolean goon = false;
	private final Ticker ps;
	private int aggressiveGCModificator;
	private RunningAverage avgFreeMemory;
	
	public MemoryChecker(Ticker ps, int modificator){
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
		freenet.support.Logger.OSThread.logPID(this);
		if(!goon){
			Logger.normal(this, "Goon is false ; killing MemoryChecker");
			return;
		}
		
		Runtime r = Runtime.getRuntime();
		
		long totalMemory = r.totalMemory();
		long freeMemory = r.freeMemory();
		long maxMemory = r.maxMemory();
		
		Logger.normal(this, "Memory in use: "+SizeUtil.formatSize((totalMemory-freeMemory)));
		
		if (totalMemory == maxMemory || maxMemory == Long.MAX_VALUE) {
			// jvm have allocated maximum memory
			// totalMemory never decrease, so check it only for once
			if (avgFreeMemory == null)
				avgFreeMemory = new SimpleRunningAverage(3, freeMemory);
			else
				avgFreeMemory.report(freeMemory);
			
			if (avgFreeMemory.countReports() >= 3 && avgFreeMemory.currentValue() < 4 * 1024 * 1024) {//  average free memory < 4 MB
				Logger.normal(this, "Reached threshold, checking for low memory ...");
				System.gc();
				System.runFinalization();

				try {
					Thread.sleep(10); // Force a context switch, finalization need a CS to complete
				} catch (InterruptedException e) {
				}

				freeMemory = r.freeMemory();
				avgFreeMemory.report(freeMemory);
				
				if (freeMemory < 4 * 1024 * 1024) { // *current* free memory < 4 MB
					Logger.error(this, "Memory too low, trying to free some");
					OOMHandler.lowMemory();
				} 
			}
		}
		
		int sleeptime = aggressiveGCModificator;
		if(sleeptime <= 0) { // We are done
			ps.queueTimedJob(this, 120 * 250); // 30 sec
			return;
		} else
			ps.queueTimedJob(this, 120L * sleeptime);
		
		// FIXME
		// Do not remove until all known memory issues fixed,
		// Especially #66
		// This probably reduces performance, but it makes
		// memory usage *more predictable*. This will make
		// tracking down the sort of nasty unpredictable OOMs
		// we are getting much easier. 
		if(aggressiveGCModificator > 0) {
			boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			long beforeGCUsedMemory = (r.totalMemory() - r.freeMemory());
			if(logMINOR) Logger.minor(this, "Memory in use before GC: "+beforeGCUsedMemory);
			long beforeGCTime = System.currentTimeMillis();
			System.gc();
			System.runFinalization();
			long afterGCTime = System.currentTimeMillis();
			long afterGCUsedMemory = (r.totalMemory() - r.freeMemory());
			if(logMINOR) {
				Logger.minor(this, "Memory in use after GC: "+afterGCUsedMemory);
				Logger.minor(this, "GC completed after "+(afterGCTime - beforeGCTime)+"ms and \"recovered\" "+(beforeGCUsedMemory - afterGCUsedMemory)+" bytes, leaving "+afterGCUsedMemory+" bytes used");
			}
		}

	}
}
