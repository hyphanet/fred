package freenet.clients.http;

/** An fproxy fetch which is stalled waiting for either the data or a progress screen. */
public class FProxyFetchWaiter {
	
	public FProxyFetchWaiter(FProxyFetchInProgress progress2) {
		this.progress = progress2;
		if(progress.finished()) finished = true;
	}

	private final FProxyFetchInProgress progress;
	
	private boolean hasWaited;
	private boolean finished;
	private boolean awoken;
	
	public FProxyFetchResult getResult() {
		synchronized(this) {
			if(!(finished || hasWaited || awoken)) {
				awoken = false;
				try {
					wait(2000);
				} catch (InterruptedException e) { 
					// Not likely
				};
			}
		}
		return progress.innerGetResult();
	}

	public void close() {
		progress.close(this);
	}
	
	synchronized void wakeUp(boolean fin) {
		if(fin)
			this.finished = true;
		else
			this.awoken = true;
		notifyAll();
	}
	
}
