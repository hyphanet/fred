package freenet.support;

public class CheatingTicker implements Ticker {
    
    final Executor underlying;

    public CheatingTicker(Executor exec) {
        underlying = exec;
    }

    @Override
    public void queueTimedJob(Runnable job, long offset) {
        underlying.execute(job);
    }

    @Override
    public void queueTimedJob(Runnable job, String name, long offset, boolean runOnTickerAnyway,
            boolean noDupes) {
        underlying.execute(job);
    }

    @Override
    public Executor getExecutor() {
        return underlying;
    }

    @Override
    public void removeQueuedJob(Runnable job) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void queueTimedJobAbsolute(Runnable job, String name, long time,
            boolean runOnTickerAnyway, boolean noDupes) {
        underlying.execute(job);
    }

}
