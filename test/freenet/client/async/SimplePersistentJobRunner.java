package freenet.client.async;

import freenet.support.Executor;

public class SimplePersistentJobRunner implements PersistentJobRunner {

    final Executor exec;
    final ClientContext context;
    
    public SimplePersistentJobRunner(Executor exec, ClientContext context) {
        this.exec = exec;
        this.context = context;
    }

    @Override
    public void queue(final PersistentJob job, int threadPriority) {
        exec.execute(new Runnable() {

            @Override
            public void run() {
                job.run(context);
            }
            
        });
    }

    @Override
    public void queueLowOrDrop(PersistentJob job) {
        queue(job, 0);
    }

    @Override
    public void setCheckpointASAP() {
        // Ignore.
    }

    @Override
    public boolean hasStarted() {
        return true;
    }

}
