package freenet.clients.http;

/** An fproxy fetch which is stalled waiting for either the data or a progress screen. */
public class FProxyFetchWaiter {
	
	public FProxyFetchWaiter(FProxyFetchInProgress progress2) {
		this.progress = progress2;
		if(progress.finished()) finished = true;
		hasWaited = progress.hasWaited();
	}

	private final FProxyFetchInProgress progress;
	
	private boolean hasWaited;
	private boolean finished;
	private boolean awoken;
	
	public FProxyFetchResult getResult() {
		boolean waited;
		synchronized(this) {
			if(!(finished || hasWaited || awoken)) {
				awoken = false;
				try {
					wait(5000);
				} catch (InterruptedException e) { 
					// Not likely
				};
				hasWaited = true;
			}
			waited = hasWaited;
		}
		progress.setHasWaited();
		return progress.innerGetResult(waited);
	}
	
	/** Returns the result, without waiting*/
	public FProxyFetchResult getResultFast(){
		return progress.innerGetResult(false);
	}

	public void close() {
		progress.close(this);
	}
	
	public synchronized void wakeUp(boolean fin) {
		if(fin)
			this.finished = true;
		else
			this.awoken = true;
		notifyAll();
	}
	
}
