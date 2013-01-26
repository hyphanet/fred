package freenet.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.client.async.DatabaseDisabledException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class NodeRestartJobsQueue {
	
	private final long nodeDBHandle;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	@SuppressWarnings("unchecked")
    public NodeRestartJobsQueue(long nodeDBHandle2) {
		nodeDBHandle = nodeDBHandle2;
		dbJobs = new Set[NativeThread.JAVA_PRIORITY_RANGE];
		dbJobsEarly = new Set[NativeThread.JAVA_PRIORITY_RANGE];
		for(int i=0;i<dbJobs.length;i++) {
			dbJobs[i] = new HashSet<DBJob>();
			dbJobsEarly[i] = new HashSet<DBJob>();
		}
	}
	
    public static NodeRestartJobsQueue init(final long nodeDBHandle, ObjectContainer container) {
    	@SuppressWarnings("serial")
		ObjectSet<NodeRestartJobsQueue> results = 
			container.query(new Predicate<NodeRestartJobsQueue>() {
			@Override
			public boolean match(NodeRestartJobsQueue arg0) {
				return (arg0.nodeDBHandle == nodeDBHandle);
			}
			
		});
		if(results.hasNext()) {
			System.err.println("Found old restart jobs queue");
			NodeRestartJobsQueue queue = results.next();
			container.activate(queue, 1);
			queue.onInit(container);
			return queue;
		}
		NodeRestartJobsQueue queue = new NodeRestartJobsQueue(nodeDBHandle);
		container.store(queue);
		System.err.println("Created new restart jobs queue");
		return queue;
	}

	private void onInit(ObjectContainer container) {
	}

	private Set<DBJob>[] dbJobs;
	private Set<DBJob>[] dbJobsEarly;
	
	public synchronized void queueRestartJob(DBJob job, int priority, ObjectContainer container, boolean early) {
		if(logMINOR) Logger.minor(this, "Queueing restart job "+job+" at priority "+priority+" early="+early);
		Set<DBJob> jobs = early ? dbJobsEarly[priority] : dbJobs[priority];
		container.store(job);
		container.activate(jobs, 1);
		if(jobs.add(job)) {
			/*
			 * Store to 1 hop only.
			 * Otherwise db4o will update ALL the jobs on the queue to a depth of 3,
			 * which in practice means all the buckets inside the BucketChainBucket's
			 * linked by the BucketChainBucketKillTag's (adding new ones). This will
			 * take ages and is in any case not what we want.
			 * See http://tracker.db4o.com/browse/COR-1436
			 */
			container.ext().store(jobs, 1);
		}
		container.deactivate(jobs, 1);
	}
	
	public synchronized void removeRestartJob(DBJob job, int priority, ObjectContainer container) {
		boolean jobWasActive = container.ext().isActive(job);
		if(!jobWasActive) container.activate(job, 1);
		container.activate(dbJobs[priority], 1);
		container.activate(dbJobsEarly[priority], 1);
		if(!(dbJobs[priority].remove(job) || dbJobsEarly[priority].remove(job))) {
			container.deactivate(dbJobs[priority], 1);
			container.deactivate(dbJobsEarly[priority], 1);
			int found = 0;
			for(int i=0;i<dbJobs.length;i++) {
				if(i==priority) continue;
				container.activate(dbJobs[i], 1);
				container.activate(dbJobsEarly[i], 1);
				if(dbJobs[i].remove(job)) {
					/*
					 * Store to 1 hop only.
					 * Otherwise db4o will update ALL the jobs on the queue to a depth of 3,
					 * which in practice means all the buckets inside the BucketChainBucket's
					 * linked by the BucketChainBucketKillTag's (adding new ones). This will
					 * take ages and is in any case not what we want.
					 * See http://tracker.db4o.com/browse/COR-1436
					 */
					container.ext().store(dbJobs[i], 1);
					found++;
				}
				if(dbJobsEarly[i].remove(job)) {
					/*
					 * Store to 1 hop only.
					 * Otherwise db4o will update ALL the jobs on the queue to a depth of 3,
					 * which in practice means all the buckets inside the BucketChainBucket's
					 * linked by the BucketChainBucketKillTag's (adding new ones). This will
					 * take ages and is in any case not what we want.
					 * See http://tracker.db4o.com/browse/COR-1436
					 */
					container.ext().store(dbJobsEarly[i], 1);
					found++;
				}
				container.deactivate(dbJobs[i], 1);
				container.deactivate(dbJobsEarly[i], 1);
			}
			if(found > 0)
				Logger.error(this, "Job "+job+" not in specified priority "+priority+" found in "+found+" other priorities when removing");
			else
				Logger.error(this, "Job "+job+" not found when removing it");
		} else {
			/*
			 * Store to 1 hop only.
			 * Otherwise db4o will update ALL the jobs on the queue to a depth of 3,
			 * which in practice means all the buckets inside the BucketChainBucket's
			 * linked by the BucketChainBucketKillTag's (adding new ones). This will
			 * take ages and is in any case not what we want.
			 * See http://tracker.db4o.com/browse/COR-1436
			 */
			container.ext().store(dbJobs[priority], 1);
			container.deactivate(dbJobs[priority], 1);
			container.ext().store(dbJobsEarly[priority], 1);
			container.deactivate(dbJobsEarly[priority], 1);
		}
		if(!jobWasActive) container.deactivate(job, 1);
	}
	
	static class RestartDBJob {
		public RestartDBJob(DBJob job2, int i) {
			job = job2;
			prio = i;
		}
		DBJob job;
		int prio;
	}

	synchronized RestartDBJob[] getEarlyRestartDatabaseJobs(ObjectContainer container) {
		ArrayList<RestartDBJob> list = new ArrayList<RestartDBJob>();
		for(int i=dbJobsEarly.length-1;i>=0;i--) {
			container.activate(dbJobsEarly[i], 1);
			if(!dbJobsEarly[i].isEmpty())
				System.err.println("Adding "+dbJobsEarly[i].size()+" early restart jobs at priority "+i);
			for(DBJob job : dbJobsEarly[i])
				list.add(new RestartDBJob(job, i));
			container.deactivate(dbJobsEarly[i], 1);
		}
		return list.toArray(new RestartDBJob[list.size()]);
	}
	
	void addLateRestartDatabaseJobs(DBJobRunner runner, ObjectContainer container) throws DatabaseDisabledException {
		for(int i=dbJobsEarly.length-1;i>=0;i--) {
			container.activate(dbJobs[i], 1);
			if(!dbJobs[i].isEmpty())
				System.err.println("Adding "+dbJobs[i].size()+" restart jobs at priority "+i);
			for(Iterator<DBJob> it = dbJobs[i].iterator();it.hasNext();) {
				DBJob job = it.next();
				if(job == null) {
					Logger.error(this, "Late restart job removed without telling the NodeRestartJobsQueue on priority "+i+"!");
					it.remove();
					container.ext().store(dbJobs[i], 2);
					continue;
				}
				container.activate(job, 1);
				runner.queue(job, i, false);
			}
		}
	}
	
}
