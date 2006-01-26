package freenet.client.async;

import java.net.MalformedURLException;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;

/**
 * A high level data request.
 */
public class ClientGetter extends ClientRequest implements GetCompletionCallback {

	final ClientCallback client;
	final FreenetURI uri;
	final FetcherContext ctx;
	final ArchiveContext actx;
	ClientGetState currentState;
	private boolean finished;
	private int archiveRestarts;
	
	public ClientGetter(ClientCallback client, ClientRequestScheduler sched, FreenetURI uri, FetcherContext ctx, short priorityClass) {
		super(priorityClass, sched);
		this.client = client;
		this.uri = uri;
		this.ctx = ctx;
		this.finished = false;
		this.actx = new ArchiveContext();
		archiveRestarts = 0;
	}
	
	public void start() throws FetchException {
		try {
			currentState = new SingleFileFetcher(this, this, new ClientMetadata(), uri, ctx, actx, getPriorityClass(), 0, false, null, true);
			currentState.schedule();
		} catch (MalformedURLException e) {
			throw new FetchException(FetchException.INVALID_URI, e);
		}
	}

	public void onSuccess(FetchResult result, ClientGetState state) {
		finished = true;
		currentState = null;
		client.onSuccess(result, this);
	}

	public void onFailure(FetchException e, ClientGetState state) {
		while(true) {
			if(e.mode == FetchException.ARCHIVE_RESTART) {
				archiveRestarts++;
				if(archiveRestarts > ctx.maxArchiveRestarts)
					e = new FetchException(FetchException.TOO_MANY_ARCHIVE_RESTARTS);
				else {
					try {
						start();
					} catch (FetchException e1) {
						e = e1;
						continue;
					}
					return;
				}
			}
			finished = true;
			client.onFailure(e, this);
			return;
		}
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

	public FreenetURI getURI() {
		return uri;
	}

	public void notifyClients() {
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks));
	}
	
}
