/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

/**
 * Interface for an object which queues and runs DBJob's.
 * @author toad
 */
public interface DBJobRunner {
	
	public void queue(DBJob job, int priority, boolean checkDupes);

	public boolean onDatabaseThread();

	public int getQueueSize(int priority);

}
