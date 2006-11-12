package freenet.node;

import java.util.ArrayList;

public class SemiOrderedShutdownHook extends Thread {
	
	static final int TIMEOUT = 100*1000;
	private final ArrayList earlyJobs;
	private final ArrayList lateJobs;
	
	public SemiOrderedShutdownHook() {
		earlyJobs = new ArrayList();
		lateJobs = new ArrayList();
	}
	
	public synchronized void addEarlyJob(Thread r) {
		earlyJobs.add(r);
	}
	
	public synchronized void addLateJob(Thread r) {
		lateJobs.add(r);
	}
	
	public void run() {
		// First run early jobs, all at once, and wait for them to all complete.
		
		for(int i=0;i<earlyJobs.size();i++) {
			Thread r = (Thread) earlyJobs.get(i);
			r.start();
		}
		for(int i=0;i<earlyJobs.size();i++) {
			Thread r = (Thread) earlyJobs.get(i);
			try {
				r.join(TIMEOUT);
			} catch (InterruptedException e) {
				// :(
				// May as well move on
			}
		}

		// Then run late jobs, all at once, and wait for them to all complete (JVM will exit when we return).
		for(int i=0;i<lateJobs.size();i++) {
			Thread r = (Thread) lateJobs.get(i);
			r.start();
		}
		for(int i=0;i<lateJobs.size();i++) {
			Thread r = (Thread) lateJobs.get(i);
			try {
				r.join(TIMEOUT);
			} catch (InterruptedException e) {
				// :(
				// May as well move on
			}
		}
		
	}

}
