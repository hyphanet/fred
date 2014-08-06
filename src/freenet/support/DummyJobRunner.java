package freenet.support;

import freenet.client.async.ClientContext;
import freenet.client.async.PersistentJob;
import freenet.client.async.PersistentJobRunner;
import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;

/** A PersistentJobRunner that isn't persistent. Convenient for transient requests, code doesn't
 * need messy if(persistent) everywhere. */
public class DummyJobRunner implements PersistentJobRunner {
    
    final Executor executor;
    final ClientContext context;

    public DummyJobRunner(Executor executor, ClientContext context) {
        this.executor = executor;
        this.context = context;
    }

    @Override
    public void queue(final PersistentJob job, final int priority) {
        executor.execute(new PrioRunnable() {

            @Override
            public void run() {
                job.run(context);
            }

            @Override
            public int getPriority() {
                return priority;
            }
            
        });
    }

    @Override
    public void queueLowOrDrop(PersistentJob job) {
        queue(job, NativeThread.NORM_PRIORITY);
    }

    @Override
    public void setCommitThisTransaction() {
        // Ignore.
    }

    @Override
    public boolean hasStarted() {
        return true;
    }

}
