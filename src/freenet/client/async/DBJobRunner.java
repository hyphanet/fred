/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

/**
 * Interface for an object which queues and runs DBJob's.
 * @author toad
 */
public interface DBJobRunner {
	
	public void queue(DBJob job, int priority, boolean checkDupes);
	
	/** Run this database job blocking. If we are already on the database thread, 
	 * run it inline, otherwise schedule it at the specified priority and wait for 
	 * it to finish. */
	public void runBlocking(DBJob job, int priority);

	public boolean onDatabaseThread();

	public int getQueueSize(int priority);
	
	/** Queue a database job to be executed just after restart.
	 * All such jobs must be completed before any bucket cleanup occurs. */
	public void queueRestartJob(DBJob job, int priority, ObjectContainer container);
	
	/** Remove a queued on-restart database job. */
	public void removeRestartJob(DBJob job, int priority, ObjectContainer container);
	
}
