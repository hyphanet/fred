package freenet.node;

import freenet.client.async.ChosenBlock;
import freenet.client.async.ClientContext;
import freenet.keys.ClientKey;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class SendableGetRequestSender implements SendableRequestSender {

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/** Do the request, blocking. Called by RequestStarter. 
	 * Also responsible for deleting it.
	 * @return True if a request was executed. False if caller should try to find another request, and remove
	 * this one from the queue. */
	public boolean send(NodeClientCore core, final RequestScheduler sched, ClientContext context, ChosenBlock req) {
		Object keyNum = req.token;
		ClientKey key = req.ckey;
		if(key == null) {
			Logger.error(SendableGet.class, "Key is null in send(): keyNum = "+keyNum+" for "+req);
			return false;
		}
		if(logMINOR)
			Logger.minor(SendableGet.class, "Sending get for key "+keyNum+" : "+key);
		if(req.isCancelled()) {
			if(logMINOR) Logger.minor(SendableGet.class, "Cancelled: "+req);
			req.onFailure(new LowLevelGetException(LowLevelGetException.CANCELLED), context);
			return false;
		}
		try {
			try {
				core.realGetKey(key, req.localRequestOnly, req.ignoreStore, req.canWriteClientCache, req.realTimeFlag);
			} catch (final LowLevelGetException e) {
				req.onFailure(e, context);
				return true;
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
				req.onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), context);
				return true;
			}
			req.onFetchSuccess(context);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			req.onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), context);
			return true;
		}
		return true;
	}

}
