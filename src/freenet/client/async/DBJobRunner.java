/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

/**
 * Interface for an object which queues and runs DBJob's. These are, apart from during startup,
 * the only way to interact with the database.
 * @author toad
 */
public interface DBJobRunner {
	
	/**
	 * Queue a database job. This will be run on the database thread, once it comes off the
	 * queue.
	 * @param job The job to run.
	 * @param priority The priority of the job. This uses NativeThread priorities, but only
	 * to determine which job to run first.
	 * @param checkDupes If true, check whether there is a job which is equals() to this job
	 * which is already queued to run, and if so don't queue the new job.
	 * @throws DatabaseDisabledException
	 */
	public void queue(DBJob job, int priority, boolean checkDupes) throws DatabaseDisabledException;
	
	/** Run this database job blocking. If we are already on the database thread, 
	 * run it inline, otherwise schedule it at the specified priority and wait for 
	 * it to finish. */
	public void runBlocking(DBJob job, int priority) throws DatabaseDisabledException;

	/** Is the caller running on the database thread? */
	public boolean onDatabaseThread();

	/** How many jobs are queued at a given priority? */
	public int getQueueSize(int priority);
	
	/** Queue a database job to be executed just after restart.
	 * @param early If true, the job will be run just after startup, at HIGH priority; the priority
	 * given determines the order of such jobs. If false, it will be queued to the database job 
	 * scheduler at the given priority. Late jobs are responsible for removing themselves! */
	public void queueRestartJob(DBJob job, int priority, ObjectContainer container, boolean early) throws DatabaseDisabledException;
	
	/** Remove a queued on-restart database job. */
	public void removeRestartJob(DBJob job, int priority, ObjectContainer container) throws DatabaseDisabledException;

	/** Has the database been killed? */
	public boolean killedDatabase();
	
	/** Tell the job runner that this transaction needs to be committed, even if the
	 * DBJob returns false. */
	public void setCommitThisTransaction();
	
	/** Tell the job runner that this transaction needs to be committed soon. */
	public void setCommitSoon();
	
}
