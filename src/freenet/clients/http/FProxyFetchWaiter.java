package freenet.clients.http;

/** An fproxy fetch which is stalled waiting for either the data or a progress screen. */
public class FProxyFetchWaiter {
	
	public FProxyFetchWaiter(FProxyFetchInProgress progress2) {
		this.progress = progress2;
		if(progress.finished()) finished = true;
		hasWaited = progress.hasWaited();
	}

	final FProxyFetchInProgress progress;
	
	private boolean hasWaited;
	private boolean finished;
	private boolean awoken;
	
	public FProxyFetchResult getResult() {
		return getResult(false);
	}
	
	public FProxyFetchResult getResult(boolean waitForever) {
		boolean waited;
		synchronized(this) {
			if(waitForever) {
				while(!finished) {
					try {
						wait();
						hasWaited = true;
					} catch (InterruptedException e) {
						// Ignore
					}
				}
			} else {
				/* Wait for 5 seconds or until something happens. The
				 * most common something other than finishing is a callback
				 * because the request has finished checking the datastore
				 * and has been sent to the network, in which case we want
				 * to show the progress bar. */
				if(!(finished || hasWaited || awoken)) {
					awoken = false;
					try {
						wait(5000);
					} catch (InterruptedException e) { 
						// Not likely
					};
					hasWaited = true;
				}
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
	
	public FProxyFetchInProgress getProgress() {
		return progress;
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
	
	public boolean hasWaited() {
		return hasWaited;
	}
	
}
