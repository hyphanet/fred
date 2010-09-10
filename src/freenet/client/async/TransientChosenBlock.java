package freenet.client.async;

import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.LowLevelGetException;
import freenet.node.LowLevelPutException;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestSender;
import freenet.support.RandomGrabArray;

/**
 * A ChosenBlock which isn't persistent.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 *
 */
public class TransientChosenBlock extends ChosenBlock {

	public final SendableRequest request;
	public final RequestScheduler sched;

	public TransientChosenBlock(SendableRequest req, SendableRequestItem token, Key key, ClientKey ckey, 
			boolean localRequestOnly, boolean ignoreStore, boolean canWriteClientCache, boolean forkOnCacheable, boolean realTimeFlag, RequestScheduler sched) {
		super(token, key, ckey, localRequestOnly, ignoreStore, canWriteClientCache, forkOnCacheable, realTimeFlag, sched);
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

	@Override
	public void onFailure(LowLevelPutException e, ClientContext context) {
		((SendableInsert) request).onFailure(e, token, null, context);
	}

	@Override
	public void onInsertSuccess(ClientContext context) {
		((SendableInsert) request).onSuccess(token, null, context);
	}

	@Override
	public void onFailure(LowLevelGetException e, ClientContext context) {
		((SendableGet) request).onFailure(e, token, null, context);
	}

	@Override
	public void onFetchSuccess(ClientContext context) {
		sched.succeeded((SendableGet)request, false);
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
