package freenet.client.async;

import java.net.MalformedURLException;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.keys.FreenetURI;

/**
 * A high level data request.
 */
public class ClientGet extends ClientRequest implements GetCompletionCallback {

	final Client client;
	final FreenetURI uri;
	final FetcherContext ctx;
	final ArchiveContext actx;
	final ClientRequestScheduler scheduler;
	ClientGetState currentState;
	private boolean finished;
	private int archiveRestarts;
	
	public ClientGet(Client client, ClientRequestScheduler sched, FreenetURI uri, FetcherContext ctx, short priorityClass) {
		super(priorityClass);
		this.client = client;
		this.uri = uri;
		this.ctx = ctx;
		this.scheduler = sched;
		this.finished = false;
		this.actx = new ArchiveContext();
		archiveRestarts = 0;
		start();
	}
	
	private void start() {
		try {
			currentState = new SingleFileFetcher(this, this, new ClientMetadata(), uri, ctx, actx, getPriorityClass(), 0, false, null);
			currentState.schedule();
		} catch (MalformedURLException e) {
			onFailure(new FetchException(FetchException.INVALID_URI, e), null);
		} catch (FetchException e) {
			onFailure(e, null);
		}
	}

	public void onSuccess(FetchResult result, ClientGetState state) {
		finished = true;
		currentState = null;
		client.onSuccess(result, this);
	}

	public void onFailure(FetchException e, ClientGetState state) {
		if(e.mode == FetchException.ARCHIVE_RESTART) {
			archiveRestarts++;
			if(archiveRestarts > ctx.maxArchiveRestarts)
				e = new FetchException(FetchException.TOO_MANY_ARCHIVE_RESTARTS);
			else {
				start();
				return;
			}
		}
		finished = true;
		client.onFailure(e, this);
	}
	
	public void cancel() {
		synchronized(this) {
			super.cancel();
			if(currentState != null)
				currentState.cancel();
		}
	}

	public boolean isFinished() {
		return finished || cancelled;
	}
	
}
