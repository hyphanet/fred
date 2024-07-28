/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.Ticker;
import freenet.support.Logger.LogLevel;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;

public class MemoryChecker implements Runnable {
	private volatile boolean goon = false;
	private final Ticker ps;
	private int aggressiveGCModificator;

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

	@Override
	public void run() {
		freenet.support.Logger.OSThread.logPID(this);
		if(!goon){
			Logger.normal(this, "Goon is false ; killing MemoryChecker");
			return;
		}
		
		Runtime r = Runtime.getRuntime();
		
		long totalMemory = r.totalMemory();
		long freeMemory = r.freeMemory();
		
		Logger.normal(this, "Memory in use: "+SizeUtil.formatSize((totalMemory-freeMemory)));
		
		int sleeptime = aggressiveGCModificator;
		if(sleeptime <= 0) { // We are done
			ps.queueTimedJob(this, 120 * 250); // 30 sec
		} else
			ps.queueTimedJob(this, 120L * sleeptime);

	}
}
