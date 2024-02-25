package freenet.node;

import freenet.client.async.ChosenBlock;
import freenet.client.async.ClientContext;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class SendableGetRequestSender implements SendableRequestSender {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(
			new LogThresholdCallback() {
				@Override
				public void shouldUpdate() {
					logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				}
			}
		);
	}

	public boolean sendIsBlocking() {
		return false;
	}

	/** Do the request, blocking. Called by RequestStarter.
	 * Also responsible for deleting it.
	 * @return True if a request was executed. False if caller should try to find another request, and remove
	 * this one from the queue. */
	@Override
	public boolean send(
		NodeClientCore core,
		final RequestScheduler sched,
		final ClientContext context,
		final ChosenBlock req
	) {
		Object keyNum = req.token;
		final ClientKey key = req.ckey;
		if (key == null) {
			Logger.error(
				SendableGet.class,
				"Key is null in send(): keyNum = " + keyNum + " for " + req
			);
			return false;
		}
		if (logMINOR) Logger.minor(
			SendableGet.class,
			"Sending get for key " + keyNum + " : " + key
		);
		if (req.isCancelled()) {
			if (logMINOR) Logger.minor(SendableGet.class, "Cancelled: " + req);
			req.onFailure(
				new LowLevelGetException(LowLevelGetException.CANCELLED),
				context
			);
			return false;
		}
		try {
			try {
				final Key k = key.getNodeKey();
				core.asyncGet(
					k,
					false,
					new RequestCompletionListener() {
						@Override
						public void onSucceeded() {
							req.onFetchSuccess(context);
						}

						@Override
						public void onFailed(LowLevelGetException e) {
							req.onFailure(e, context);
						}
					},
					!req.ignoreStore,
					req.canWriteClientCache,
					req.realTimeFlag,
					req.localRequestOnly,
					req.ignoreStore
				);
			} catch (Throwable t) {
				Logger.error(this, "Caught " + t, t);
				req.onFailure(
					new LowLevelGetException(
						LowLevelGetException.INTERNAL_ERROR
					),
					context
				);
				return true;
			}
		} catch (Throwable t) {
			Logger.error(this, "Caught " + t, t);
			req.onFailure(
				new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR),
				context
			);
			return true;
		}
		return true;
	}
}
