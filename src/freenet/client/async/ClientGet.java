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
public class ClientGet extends ClientRequest implements RequestCompletionCallback {

	final Client client;
	final FreenetURI uri;
	final FetcherContext ctx;
	final ArchiveContext actx;
	final ClientRequestScheduler scheduler;
	ClientGetState fetchState;
	private boolean finished;
	private boolean cancelled;
	final int priorityClass;
	private int archiveRestarts;
	
	public ClientGet(Client client, ClientRequestScheduler sched, FreenetURI uri, FetcherContext ctx, short priorityClass) {
		super(priorityClass);
		this.client = client;
		this.uri = uri;
		this.ctx = ctx;
		this.scheduler = sched;
		this.finished = false;
		this.actx = new ArchiveContext();
		this.priorityClass = priorityClass;
		archiveRestarts = 0;
		start();
	}
	
	private void start() {
		try {
			fetchState = new SingleFileFetcher(this, this, new ClientMetadata(), uri, ctx, actx, priorityClass, 0, false, null);
			fetchState.schedule();
		} catch (MalformedURLException e) {
			onFailure(new FetchException(FetchException.INVALID_URI, e), null);
		} catch (FetchException e) {
			onFailure(e, null);
		}
	}

	public void cancel() {
		cancelled = true;
	}
	
	public boolean isCancelled() {
		return cancelled;
	}
	
	public void onSuccess(FetchResult result, ClientGetState state) {
		finished = true;
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
	
}
