/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;


public interface Ticker {

    /** Run a job after a given delay.
     * @param job The Runnable to execute.
     * @param offset The delay in milliseconds.
     */
	public abstract void queueTimedJob(Runnable job, long offset);
	
	/** Run a job after a given delay.
	 * @param job The Runnable to execute.
	 * @param name The name of the thread to run it.
	 * @param offset The delay in milliseconds.
	 * @param runOnTickerAnyway If true, start the job from the Ticker thread even if we could pass
	 * it directly through to the Executor. This is needed for increasing thread priorities, since
	 * the Ticker runs at a high priority, and for some tests.
	 * @param noDupes If true, ignore the job if it is already queued. Implies runOnTickerAnyway.
	 * WARNING: This does not guarantee that we don't run multiple copies of the job 
     * simultaneously! You must ensure adequate locking. Worse, if the job takes an unexpectedly 
     * long time, you could end up with many copies of the job running simultaneously. 
	 */
	public abstract void queueTimedJob(Runnable job, String name, long offset, boolean runOnTickerAnyway, boolean noDupes);
	
	/** Get the underlying Executor. */
	public abstract Executor getExecutor();
	
	/** Remove a queued job. */
	public abstract void removeQueuedJob(Runnable job);
	
	/** Run a job at a specified absolute time.
     * @param job The Runnable to execute.
     * @param name The name of the thread to run it.
	 * @param time The time at which to run the job.
     * @param runOnTickerAnyway If true, start the job from the Ticker thread even if we could pass
     * it directly through to the Executor. This is needed for increasing thread priorities, since
     * the Ticker runs at a high priority, and for some tests.
     * @param noDupes If true, ignore the job if it is already queued. Implies runOnTickerAnyway.
     * WARNING: This does not guarantee that we don't run multiple copies of the job 
     * simultaneously! You must ensure adequate locking. Worse, if the job takes an unexpectedly 
     * long time, you could end up with many copies of the job running simultaneously. 
	 */
    public abstract void queueTimedJobAbsolute(Runnable runner, String name, long time, 
            boolean runOnTickerAnyway, boolean noDupes);

}