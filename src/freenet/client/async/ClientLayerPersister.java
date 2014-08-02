package freenet.client.async;

import static java.util.concurrent.TimeUnit.SECONDS;
import freenet.support.Executor;

public class ClientLayerPersister extends PersistentJobRunnerImpl {
    
    static final long INTERVAL = SECONDS.toMillis(30);

    public ClientLayerPersister(Executor executor) {
        super(executor, INTERVAL);
    }

    @Override
    protected void innerCheckpoint() {
        // TODO Auto-generated method stub

    }

}
