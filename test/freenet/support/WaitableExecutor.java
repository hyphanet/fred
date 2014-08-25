package freenet.support;

public class WaitableExecutor implements Executor {
    
    public WaitableExecutor(Executor exec) {
        this.underlying = exec;
    }
    
    public class Wrapper implements Runnable {
        
        final Runnable job;

        public Wrapper(Runnable job) {
            this.job = job;
        }

        @Override
        public void run() {
            try {
                job.run();
            } finally {
                synchronized(WaitableExecutor.this) {
                    count--;
                    if(count == 0) WaitableExecutor.this.notifyAll();
                }
            }
        }

    }

    private final Executor underlying;
    private int count;

    @Override
    public void execute(Runnable job) {
        synchronized(this) {
            count++;
        }
        underlying.execute(new Wrapper(job));
    }

    @Override
    public void execute(Runnable job, String jobName) {
        synchronized(this) {
            count++;
        }
        underlying.execute(new Wrapper(job), jobName);
    }

    @Override
    public void execute(Runnable job, String jobName, boolean fromTicker) {
        synchronized(this) {
            count++;
        }
        underlying.execute(new Wrapper(job), jobName, fromTicker);
    }

    @Override
    public int[] waitingThreads() {
        return underlying.waitingThreads();
    }

    @Override
    public int[] runningThreads() {
        return underlying.runningThreads();
    }

    @Override
    public int getWaitingThreadsCount() {
        return underlying.getWaitingThreadsCount();
    }
    
    public synchronized void waitForIdle() {
        while(count > 0)
            try {
                wait();
            } catch (InterruptedException e) {
                // Ignore.
            }
    }

    public synchronized boolean isIdle() {
        return count == 0;
    }

}
