package freenet.support;

import freenet.client.async.ClientContext;
import freenet.client.async.PersistentJob;
import freenet.client.async.PersistentJobRunner;
import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;

/** A PersistentJobRunner that isn't persistent. Convenient for transient requests, code doesn't
 * need messy if(persistent) everywhere. */
public class DummyJobRunner implements PersistentJobRunner {
    
    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(DummyJobRunner.class);
    }
    
    final Executor executor;
    final ClientContext context;

    public DummyJobRunner(Executor executor, ClientContext context) {
        this.executor = executor;
        this.context = context;
    }

    @Override
    public void queue(final PersistentJob job, final int priority) {
        if(logMINOR) Logger.minor(this, "Running job off thread: "+job);
        executor.execute(new PrioRunnable() {

            @Override
            public void run() {
                if(logMINOR) Logger.minor(this, "Starting job "+job);
                job.run(context);
            }

            @Override
            public int getPriority() {
                return priority;
            }
            
        });
    }

    @Override
    public void queueNormalOrDrop(PersistentJob job) {
        queue(job, NativeThread.NORM_PRIORITY);
    }

    @Override
    public void setCheckpointASAP() {
        // Ignore.
    }

    @Override
    public boolean hasLoaded() {
        return true;
    }

    @Override
    public void queueInternal(PersistentJob job, int threadPriority) {
        queue(job, threadPriority);
    }

    @Override
    public void queueInternal(PersistentJob job) {
        queueInternal(job, NativeThread.NORM_PRIORITY);
    }

    @Override
    public CheckpointLock lock() {
        return new CheckpointLock() {

            @Override
            public void unlock(boolean forceWrite, int threadPriority) {
                // Do nothing.
            }
            
        };
    }

    @Override
    public boolean newSalt() {
        return false;
    }

    @Override
    public boolean shuttingDown() {
        return false;
    }

}
