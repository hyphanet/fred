package freenet.node;

import java.util.ArrayList;

public class SemiOrderedShutdownHook extends Thread {
	
	private static final int TIMEOUT = 100*1000;
	private final ArrayList<Thread> earlyJobs;
	private final ArrayList<Thread> lateJobs;
	
	public static final SemiOrderedShutdownHook singleton = new SemiOrderedShutdownHook();
	
	static {
		Runtime.getRuntime().addShutdownHook(singleton);
	}
	
	public static SemiOrderedShutdownHook get() {
		return singleton;
	}
	
	private SemiOrderedShutdownHook() {
		earlyJobs = new ArrayList<Thread>();
		lateJobs = new ArrayList<Thread>();
	}
	
	public synchronized void addEarlyJob(Thread r) {
		earlyJobs.add(r);
	}
	
	public synchronized void addLateJob(Thread r) {
		lateJobs.add(r);
	}
	
	@Override
	public void run() {
		System.err.println("Shutting down...");
		// First run early jobs, all at once, and wait for them to all complete.
		
		for(Thread r : earlyJobs) {
			r.start();
		}
		for(Thread r : earlyJobs) {
			try {
				r.join(TIMEOUT);
			} catch (InterruptedException e) {
				// :(
				// May as well move on
			}
		}

		// Then run late jobs, all at once, and wait for them to all complete (JVM will exit when we return).
		for(Thread r : lateJobs) {
			r.start();
		}
		for(Thread r : lateJobs) {
			try {
				r.join(TIMEOUT);
			} catch (InterruptedException e) {
				// :(
				// May as well move on
			}
		}
		
	}
}