package freenet.client.async;

import java.util.ArrayList;
import java.util.List;

import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.io.NativeThread;

/** Runs PersistentJob's and periodically, or on demand, suspends all jobs and calls 
 * innerCheckpoint(). */
public abstract class PersistentJobRunnerImpl implements PersistentJobRunner {
    
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;
    static {
        Logger.registerClass(PersistentJobRunnerImpl.class);
    }
    
    final Executor executor;
    final Ticker ticker;
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
    protected Object serializeCheckpoints = new Object();
    private boolean willCheck = false;
    /** Have we enableCheckpointing the loading process? If so, we should accept jobs. */
    private boolean loading = false;
    /** Is checkpointing enabled at the moment? */
    private boolean enableCheckpointing = false;
    /** Have we loaded from disk at least once, regardless of enableCheckpointing? */
    private boolean loaded = false;
    /** True if checkpoint is in progress */
    private boolean writing = false;
    /** True if we should reject all new jobs */
    private boolean killed = false;

    public PersistentJobRunnerImpl(Executor executor, Ticker ticker, long interval) {
        this.executor = executor;
        this.ticker = ticker;
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
    public void queue(PersistentJob job, int threadPriority) throws PersistenceDisabledException {
        synchronized(sync) {
            if(!loading) throw new PersistenceDisabledException();
            if(killed) throw new PersistenceDisabledException();
            if(context == null) throw new IllegalStateException();
            if(mustCheckpoint && enableCheckpointing) {
                if(logDEBUG) Logger.debug(this, "Queueing job "+job);
                queuedJobs.add(new QueuedJob(job, threadPriority));
            } else {
                if(logDEBUG) Logger.debug(this, "Running job "+job);
                executor.execute(new JobRunnable(job, threadPriority, context));
                runningJobs++;
            }
        }
    }
    
    @Override
    public void queueInternal(PersistentJob job, int threadPriority) throws PersistenceDisabledException {
        synchronized(sync) {
            if(!loading) throw new PersistenceDisabledException();
            if(killed) throw new PersistenceDisabledException();
            if(context == null) throw new IllegalStateException();
            if(writing) {
                Logger.error(this, "Internal job must not be queued during writing! They should have finished before we start writing and cannot be started \"externally\"!", new Exception("error"));
                queuedJobs.add(new QueuedJob(job, threadPriority));
            } else {
                if(mustCheckpoint) {
                    if(logMINOR) Logger.minor(this, "Delaying checkpoint...");
                }
                runningJobs++;
                if(logDEBUG) Logger.debug(this, "Running job "+job);
                executor.execute(new JobRunnable(job, threadPriority, context));
            }
        }
    }
    
    @Override
    public void queueInternal(PersistentJob job) {
        try {
            queueInternal(job, NativeThread.NORM_PRIORITY);
        } catch (PersistenceDisabledException e) {
            // Maybe this could happen ... panic button maybe?
            Logger.error(this, "Dropping internal job because persistence has been turned off!: "+e, e);
        }
    }
    
    @Override
    public void queueNormalOrDrop(PersistentJob job) {
        try {
            queue(job, NativeThread.NORM_PRIORITY);
        } catch (PersistenceDisabledException e) {
            return;
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
                if(logDEBUG) Logger.debug(this, "Starting "+job);
                ret = job.run(context);
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t+" running job "+job, t);
            } finally {
                if(logDEBUG) Logger.debug(this, "Completed "+job+" with mustCheckpoint="+mustCheckpoint+" enableCheckpointing="+enableCheckpointing+" runningJobs="+runningJobs);
                handleCompletion(ret, threadPriority);
            }
        }
        
    }
    
    public void handleCompletion(boolean ret, int threadPriority) {
        synchronized(sync) {
            runningJobs--;
            if(runningJobs == 0)
                // Even if not going to checkpoint indirectly, somebody might be waiting, need to notify.
                sync.notifyAll();
            if(!enableCheckpointing) {
                if(logMINOR) Logger.minor(this, "Not enableCheckpointing yet");
                return;
            }
            if(ret) {
                mustCheckpoint = true;
                if(logMINOR) Logger.minor(this, "Writing because asked to");
            }
            if(!mustCheckpoint) {
                if(System.currentTimeMillis() - lastCheckpointed > checkpointInterval) {
                    mustCheckpoint = true;
                    if(logMINOR) Logger.minor(this, "Writing at interval");
                }
            }
            if(!mustCheckpoint) {
                delayedCheckpoint();
                return;
            }
            if(runningJobs != 0) {
                if(logDEBUG) Logger.debug(this, "Not writing yet");
                return;
            }
            if(!killed) {
                writing = true;
                if(threadPriority < WRITE_AT_PRIORITY) {
                    checkpointOffThread();
                    return;
                }
            }
        }
        checkpoint(false);
    }

    private class QueuedJob {
        public QueuedJob(PersistentJob job, int threadPriority) {
            this.job = job;
            this.threadPriority = threadPriority;
        }
        final PersistentJob job;
        final int threadPriority;
    }

    private void checkpoint(boolean shutdown) {
        if(logMINOR) Logger.minor(this, "Writing checkpoint...");
        synchronized(sync) {
            if(!enableCheckpointing) {
                writing = false;
                sync.notifyAll();
                return;
            }
        }
        synchronized(serializeCheckpoints) {
            try {
                innerCheckpoint(shutdown);
            } catch (Throwable t) {
                Logger.error(this, "Unable to save: "+t, t);
            }
        }
        synchronized(sync) {
            mustCheckpoint = false;
            writing = false;
            QueuedJob[] jobs = queuedJobs.toArray(new QueuedJob[queuedJobs.size()]);
            if(logDEBUG) Logger.debug(this, "Starting "+jobs.length+" queued jobs");
            for(QueuedJob job : jobs) {
                runningJobs++;
                executor.execute(new JobRunnable(job.job, job.threadPriority, context));
            }
            updateLastCheckpointed();
            queuedJobs.clear();
            sync.notifyAll();
        }
        if(logMINOR) Logger.minor(this, "Completed writing checkpoint");
    }
    
    public void delayedCheckpoint() {
        synchronized(sync) {
            if(killed || !enableCheckpointing) return;
            if(willCheck) return;
            ticker.queueTimedJob(new PrioRunnable() {
                
                @Override
                public void run() {
                    synchronized(sync) {
                        willCheck = false;
                        if(!(mustCheckpoint || 
                                System.currentTimeMillis() - lastCheckpointed > checkpointInterval))
                            return;
                        if(killed || !enableCheckpointing) return;
                        if(runningJobs != 0) return;
                        writing = true;
                    }
                    checkpoint(false);
                }
                
                @Override
                public int getPriority() {
                    return WRITE_AT_PRIORITY;
                }
                
            }, checkpointInterval);
            willCheck = true;
        }
    }

    public void checkpointOffThread() {
        executor.execute(new PrioRunnable() {

            @Override
            public void run() {
                synchronized(sync) {
                    if(killed || !enableCheckpointing) {
                        writing = false;
                        sync.notifyAll();
                        return;
                    }
                }
                checkpoint(false);
            }

            @Override
            public int getPriority() {
                return WRITE_AT_PRIORITY;
            }
            
        });
    }

    public void setCheckpointASAP() {
        synchronized(sync) {
            if(!enableCheckpointing) return;
            mustCheckpoint = true;
            if(runningJobs != 0) return;
        }
        checkpointOffThread();
    }
    
    protected void updateLastCheckpointed() {
        lastCheckpointed = System.currentTimeMillis();
    }

    protected abstract void innerCheckpoint(boolean shutdown);
    
    protected void onLoading() {
        synchronized(sync) {
            loading = true;
        }
    }
    
    protected void onStarted(boolean noWrite) {
        synchronized(sync) {
            loading = true;
            if(!noWrite)
                enableCheckpointing = true;
            loaded = true;
            updateLastCheckpointed();
            writing = true;
        }
        checkpointOffThread();
    }
    
    public void shutdown() {
        synchronized(sync) {
            killed = true;
        }
    }
    
    @Override
    public boolean shuttingDown() {
        synchronized(sync) {
            return killed;
        }
    }
    
    /** Typically called after shutdown() to wait for current jobs to complete. Does not check 
     * killed for this reason. */
    public void waitForIdleAndCheckpoint() {
        synchronized(sync) {
            while(runningJobs > 0 || writing) {
                if(!enableCheckpointing) return;
                System.out.println("Waiting to shutdown: "+runningJobs+" running"+(writing ? " (writing)" : ""));
                try {
                    sync.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
        checkpoint(true);
    }
    
    /** Wait until a checkpoint has been completed, or if the job runner becomes idle, do it here.
     * @throws PersistenceDisabledException */
    public void waitAndCheckpoint() throws PersistenceDisabledException {
        synchronized(sync) {
            if(!enableCheckpointing) return;
            // Set flag to ensure further jobs are queued, we want to write soon!
            mustCheckpoint = true;
            while(runningJobs > 0) {
                if(!enableCheckpointing) return;
                if(killed) throw new PersistenceDisabledException();
                Logger.error(this, "Waiting for "+runningJobs+" to finish before checkpoint");
                try {
                    sync.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
            if(writing) {
                while(writing) {
                    if(!enableCheckpointing) return;
                    if(killed) throw new PersistenceDisabledException();
                    try {
                        sync.wait();
                    } catch (InterruptedException e) {
                        // Ignore.
                    }
                }
                return;
            }
            writing = true;
        }
        checkpoint(true);
    }

    /** Set the killed flag and wait until we are not writing */
    protected void killAndWaitForNotWriting() {
        synchronized(sync) {
            killed = true;
            while(writing) {
                try {
                    sync.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }
    
    public void waitForNotWriting() {
        synchronized(sync) {
            while(writing) {
                try {
                    sync.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }

    public void killAndWaitForNotRunning() {
        synchronized(sync) {
            killed = true;
            while(runningJobs > 0 || writing) {
                try {
                    sync.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }

    public boolean isKilledOrNotLoaded() {
        synchronized(sync) {
            return killed || !loaded;
        }
    }
    
    public boolean hasLoaded() {
        synchronized(sync) {
            return loaded;
        }
    }
    
    protected ClientContext getClientContext() {
        return context;
    }
    
    public CheckpointLock lock() throws PersistenceDisabledException {
        synchronized(sync) {
            if(killed) throw new PersistenceDisabledException();
            while(writing || (mustCheckpoint && enableCheckpointing)) {
                try {
                    sync.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
                if(killed) throw new PersistenceDisabledException();
            }
            runningJobs++;
        }
        return new CheckpointLock() {

            @Override
            public void unlock(boolean forceWrite, int threadPriority) {
                handleCompletion(forceWrite, threadPriority);
            }
            
        };
    }

    public void disableWrite() {
        synchronized(sync) {
            enableCheckpointing = false;
            mustCheckpoint = false;
            sync.notifyAll();
        }
    }
    
    boolean mustCheckpoint() {
        synchronized(sync) {
            return mustCheckpoint;
        }
    }

}
