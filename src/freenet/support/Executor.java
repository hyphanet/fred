/**
 * @author toad
 * To the extent that this is copyrightable, it's part of Freenet and licensed
 * under GPL2 or later. However, it's a trivial interface taken from Sun/Oracle JDK 1.5,
 * and we will use that when we migrate to 1.5.
 */
package freenet.support;

/**
** Note that unlike {@link java.util.concurrent.Executor}, none of these run
** methods throw {@link java.util.concurrent.RejectedExecutionException}.
*/
public interface Executor extends java.util.concurrent.Executor {

	/** Execute a job. */
	public void execute(Runnable job);
	public void execute(Runnable job, String jobName);
	public void execute(Runnable job, String jobName, boolean fromTicker);

	/** Count the number of threads waiting for work at each priority level */
	public int[] waitingThreads();
	/** Count the number of threads running at each priority level */
	public int[] runningThreads();

	/** Fast method returning how many threads are waiting */
	public int getWaitingThreadsCount();
}
