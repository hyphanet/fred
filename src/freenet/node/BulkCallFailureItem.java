package freenet.node;

import freenet.client.async.PersistentChosenRequest;

public class BulkCallFailureItem {
	
	public final LowLevelGetException e;
	public final Object token;
	/** Removed by ClientRequestScheduler, implementor of SupportsBulkCallFailure should ignore. */
	public final PersistentChosenRequest req;
	
	public BulkCallFailureItem(LowLevelGetException e, Object token, PersistentChosenRequest req) {
		this.e = e;
		this.token = token;
		this.req = req;
	}

}
