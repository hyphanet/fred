/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.keys.ClientKey;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * Callback interface for a low level insert, which is immediately sendable. These
 * should be registered on the ClientRequestScheduler when we want to send them. It will
 * then, when it is time to send, create a thread, send the request, and call the 
 * callback below.
 */
public abstract class SendableInsert extends SendableRequest {

	/**
	 * zero arg c'tor for db4o on jamvm
	 */
	protected SendableInsert() {
	}

	public SendableInsert(boolean persistent, boolean realTimeFlag) {
		super(persistent, realTimeFlag);
	}
	
	/** Called when we successfully insert the data */
	public abstract void onSuccess(Object keyNum, ObjectContainer container, ClientContext context);
	
	/** Called when we don't! */
	public abstract void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context);

	@Override
	public void internalError(Throwable t, RequestScheduler sched, ObjectContainer container, ClientContext context, boolean persistent) {
		Logger.error(this, "Internal error on "+this+" : "+t, t);
		sched.callFailure(this, new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, t.getMessage(), t), NativeThread.MAX_PRIORITY, persistent);
	}

	@Override
	public final boolean isInsert() {
		return true;
	}
	
	@Override
	public ClientRequestScheduler getScheduler(ObjectContainer container, ClientContext context) {
		if(isSSK())
			return context.getSskInsertScheduler(realTimeFlag);
		else
			return context.getChkInsertScheduler(realTimeFlag);
	}

	public abstract boolean canWriteClientCache(ObjectContainer container);
	
	public abstract boolean localRequestOnly(ObjectContainer container);

	public abstract boolean forkOnCacheable(ObjectContainer container);

	/** Encoded a key */
	public abstract void onEncode(SendableRequestItem token, ClientKey key, ObjectContainer container, ClientContext context);
	
	public abstract boolean isEmpty(ObjectContainer container);
	
	@Override
	public long getCooldownTime(ObjectContainer container, ClientContext context, long now) {
		if(isEmpty(container)) return -1;
		return 0;
	}

}
