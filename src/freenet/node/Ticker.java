package freenet.node;

public interface Ticker {

	public abstract void queueTimedJob(Runnable job, long offset);

}