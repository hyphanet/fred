package freenet.client.connection;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Timer;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;
import freenet.client.l10n.L10n;
import freenet.client.messages.Message;
import freenet.client.messages.MessageManager;
import freenet.client.messages.Priority;
import freenet.client.tools.FreenetRequest;
import freenet.client.tools.QueryParameter;

/**
 * This ConnectionManager sends a keepalive message periodically to notify the server that the page is still open. If a keepalive fails, it tells the client that the server already
 * cleaned up this request.
 */
public class KeepaliveManager implements IConnectionManager {

	/** The timer that schedules the periodic message */
	private KeepaliveTimer	timer			= new KeepaliveTimer();

	/** Is it cancelled already? */
	private boolean			cancelled		= false;

	/** Does the first keepalive succeded? */
	private boolean			firstSuccess	= false;

	@Override
	public void closeConnection() {
		timer.cancel();
		// If it wasn't cancelled, then we show a message about pushing cancelled. It makes sure that this message shows only once
		if (cancelled == false) {
			if (FreenetJs.isPushingCancelledExpected == false) {
				MessageManager.get().addMessage(new Message(L10n.get("pushingCancelled"), Priority.ERROR, null, true));
			}
			cancelled = true;
		}
	}

	@Override
	public void openConnection() {
		timer.run();
		timer.scheduleRepeating(UpdaterConstants.KEEPALIVE_INTERVAL_SECONDS * 1000);
	}

	/** This class is a Timer that sends a keepalive message periodically */
	private class KeepaliveTimer extends Timer {
		@Override
		public void run() {
			FreenetRequest.sendRequest(UpdaterConstants.keepalivePath, new QueryParameter("requestId", FreenetJs.requestId), new RequestCallback() {
				@Override
				public void onResponseReceived(Request request, Response response) {
					// If not success, then close the connection
					if (response.getText().compareTo(UpdaterConstants.SUCCESS) != 0) {
						if (firstSuccess == false) {
							FreenetJs.isPushingCancelledExpected = true;
						}
						closeConnection();
					} else {
						firstSuccess = true;
					}
				}

				@Override
				public void onError(Request request, Throwable exception) {
					// If the server responded with error, close the connection
					closeConnection();
				}
			});
		}
	}
}
