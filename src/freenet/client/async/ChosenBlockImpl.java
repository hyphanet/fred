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

/**
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 *
 */
public class ChosenBlockImpl extends ChosenBlock {

	public final SendableRequest request;
	public final RequestScheduler sched;
	public final boolean persistent;

	public ChosenBlockImpl(SendableRequest req, SendableRequestItem token, Key key, ClientKey ckey, 
			boolean localRequestOnly, boolean ignoreStore, boolean canWriteClientCache, boolean forkOnCacheable, boolean realTimeFlag, RequestScheduler sched, boolean persistent) {
		super(token, key, ckey, localRequestOnly, ignoreStore, canWriteClientCache, forkOnCacheable, realTimeFlag, sched);
		this.request = req;
		this.sched = sched;
		this.persistent = persistent;
	}

	@Override
	public boolean isCancelled() {
		return request.isCancelled();
	}

	@Override
	public boolean isPersistent() {
		return persistent;
	}

	@Override
	public void onFailure(final LowLevelPutException e, ClientContext context) {
	    context.getJobRunner(persistent).queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                ((SendableInsert) request).onFailure(e, token, context);
                return false;
            }
	        
	    });
	}

	@Override
	public void onInsertSuccess(final ClientKey key, ClientContext context) {
        context.getJobRunner(persistent).queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                ((SendableInsert) request).onSuccess(token, key, context);
                return false;
            }
            
        });
	}

	@Override
	public void onFailure(final LowLevelGetException e, ClientContext context) {
        context.getJobRunner(persistent).queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                ((SendableGet) request).onFailure(e, token, context);
                return false;
            }

        });
	}

	@Override
	public void onFetchSuccess(ClientContext context) {
	    context.getJobRunner(persistent).queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                sched.succeeded((SendableGet)request, false);
                return false;
            }
	        
	    });
	}
	
	@Override
	public short getPriority() {
		return request.getPriorityClass();
	}

	@Override
	public SendableRequestSender getSender(ClientContext context) {
		return request.getSender(context);
	}
	
}
