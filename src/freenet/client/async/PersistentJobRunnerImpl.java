package freenet.client.async;

import java.util.ArrayList;
import java.util.List;

import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/** Runs PersistentJob's and periodically, or on demand, suspends all jobs and calls 
 * innerCheckpoint(). */
public abstract class PersistentJobRunnerImpl implements PersistentJobRunner {
    
    final Executor executor;
    /** The number of jobs actually running. */
    private int runningJobs;
    /** If true, we must suspend and write to disk. */
    private boolean mustCheckpoint;
    /** Jobs queued to run after the write finishes. */
    private final List<QueuedJob> queuedJobs;
    private ClientContext context;
    private long lastCheckpointed;
    static final int WRITE_AT_PRIORITY = NativeThread.HIGH_PRIORITY-1;
    final long checkpointInterval;
    /** Not to be used by child classes. */
    private Object sync = new Object();

    public PersistentJobRunnerImpl(Executor executor, long interval) {
        this.executor = executor;
        queuedJobs = new ArrayList<QueuedJob>();
        lastCheckpointed = System.currentTimeMillis();
        this.checkpointInterval = interval;
    }
    
    public void start(ClientContext context) {
        synchronized(sync) {
            this.context = context;
        }
    }

    @Override
    public void queue(PersistentJob job, int threadPriority) {
        synchronized(sync) {
            if(context == null) throw new IllegalStateException();
            if(mustCheckpoint) {
                queuedJobs.add(new QueuedJob(job, threadPriority));
            } else {
                runningJobs++;
                executor.execute(new JobRunnable(job, threadPriority, context));
            }
        }
    }
    
    private class JobRunnable implements Runnable {
        
        private final int threadPriority;
        private final PersistentJob job;
        private final ClientContext context;

        public JobRunnable(PersistentJob job, int threadPriority, ClientContext context) {
            this.job = job;
            this.threadPriority = threadPriority;
            this.context = context;
        }

        @Override
        public void run() {
            boolean ret = false;
            try {
                ret = job.run(context);
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t+" running job "+job, t);
            } finally {
                synchronized(sync) {
                    if(ret) mustCheckpoint = true;
                    if(!mustCheckpoint) {
                        mustCheckpoint = 
                            (System.currentTimeMillis() - lastCheckpointed > checkpointInterval);
                    }
                    runningJobs--;
                    if(!(mustCheckpoint && runningJobs == 0)) return;
                    if(threadPriority < WRITE_AT_PRIORITY) {
                        executor.execute(new PrioRunnable() {

                            @Override
                            public void run() {
                                checkpoint();
                            }

                            @Override
                            public int getPriority() {
                                return WRITE_AT_PRIORITY;
                            }
                            
                        });
                        return;
                    }
                }
                checkpoint();
            }
        }
        
    }
    
    private class QueuedJob {
        public QueuedJob(PersistentJob job, int threadPriority) {
            this.job = job;
            this.threadPriority = threadPriority;
        }
        final PersistentJob job;
        final int threadPriority;
    }

    private void checkpoint() {
        innerCheckpoint();
        synchronized(sync) {
            mustCheckpoint = false;
            QueuedJob[] jobs = queuedJobs.toArray(new QueuedJob[queuedJobs.size()]);
            for(QueuedJob job : jobs) {
                runningJobs++;
                executor.execute(new JobRunnable(job.job, job.threadPriority, context));
            }
        }
    }
    
    public void setCommitThisTransaction() {
        synchronized(sync) {
            mustCheckpoint = true;
        }
    }

    protected abstract void innerCheckpoint();

}
