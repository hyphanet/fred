/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.io;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;

import freenet.support.Logger;

public class SegmentedBucketChainBucketKillJob implements DBJob {
    private final int RESTART_PRIO = NativeThread.HIGH_PRIORITY;
    final SegmentedBucketChainBucket bcb;

    public SegmentedBucketChainBucketKillJob(SegmentedBucketChainBucket bucket) {
        bcb = bucket;
    }

    @Override
    public boolean run(ObjectContainer container, ClientContext context) {
        container.activate(bcb, 2);
        Logger.normal(this, "Freeing unfinished unstored bucket " + this);

        // Restart jobs runner will remove us from the queue.
        // This may take more than one transaction ...
        if (bcb.removeContents(container)) {

            // More work needs to be done.
            // We will have already been removed, so re-add, in case we crash soon.
            try {
                scheduleRestart(container, context);
            } catch (DatabaseDisabledException e1) {

                // Impossible.
                return true;
            }

            context.persistentBucketFactory.addBlobFreeCallback(this);

            // But try to sort it out now ...
            try {
                context.jobRunner.queue(this, NativeThread.NORM_PRIORITY, true);
            } catch (DatabaseDisabledException e) {

                // Impossible.
            }
        } else {
            try {
                context.jobRunner.removeRestartJob(this, RESTART_PRIO, container);
            } catch (DatabaseDisabledException e) {

                // Impossible.
            }

            container.delete(this);
            context.persistentBucketFactory.removeBlobFreeCallback(this);
        }

        return true;
    }

    public void scheduleRestart(ObjectContainer container, ClientContext context) throws DatabaseDisabledException {
        context.jobRunner.queueRestartJob(this, RESTART_PRIO, container, true);
    }
}
