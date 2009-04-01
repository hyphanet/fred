package freenet.client.async;

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.node.LowLevelGetException;
import freenet.node.LowLevelPutException;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestSender;

/**
 * A ChosenBlock which isn't persistent.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 *
 */
public class TransientChosenBlock extends ChosenBlock {

	public final SendableRequest request;
	public final RequestScheduler sched;

	public TransientChosenBlock(SendableRequest req, SendableRequestItem token, Key key, ClientKey ckey, 
			boolean localRequestOnly, boolean cacheLocalRequests, boolean ignoreStore, RequestScheduler sched) {
		super(token, key, ckey, localRequestOnly, cacheLocalRequests, ignoreStore, sched);
		this.request = req;
		this.sched = sched;
	}

	@Override
	public boolean isCancelled() {
		return request.isCancelled(null);
	}

	@Override
	public boolean isPersistent() {
		return false;
	}

	public void onFailure(LowLevelPutException e, ClientContext context) {
		((SendableInsert) request).onFailure(e, token, null, context);
	}

	public void onInsertSuccess(ClientContext context) {
		((SendableInsert) request).onSuccess(token, null, context);
	}

	public void onFailure(LowLevelGetException e, ClientContext context) {
		((SendableGet) request).onFailure(e, token, null, context);
	}

	public void onSuccess(ClientKeyBlock data, boolean fromStore, ClientContext context) {
		((SendableGet) request).onSuccess(data, fromStore, token, null, context);
	}

	@Override
	public void onFetchSuccess(ClientContext context) {
		sched.succeeded((SendableGet)request, this);
	}

	@Override
	public short getPriority() {
		return request.getPriorityClass(null);
	}

	@Override
	public SendableRequestSender getSender(ClientContext context) {
		return request.getSender(null, context);
	}
	
}
