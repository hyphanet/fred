/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

public interface ExecutorIdleCallback {

    /**
     * Called when the executor is idle for some period. On a single-thread executor,
     * this will be called on the thread which runs the jobs, but after that the thread
     * may change. 
     */
    public void onIdle();
}
