/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;


public interface Ticker {

	public abstract void queueTimedJob(Runnable job, long offset);
	public abstract void queueTimedJob(Runnable job, String name, long offset, boolean runOnTickerAnyway, boolean noDupes);
	public abstract Executor getExecutor();
	public abstract void removeQueuedJob(Runnable job);

}