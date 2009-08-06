package freenet.client.connection;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Timer;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;
import freenet.client.messages.Message;
import freenet.client.messages.MessageManager;
import freenet.client.messages.Priority;
import freenet.client.tools.FreenetRequest;
import freenet.client.tools.QueryParameter;

public class KeepaliveManager implements IConnectionManager {

	private KeepaliveTimer	timer		= new KeepaliveTimer();

	private boolean			cancelled	= false;

	@Override
	public void closeConnection() {
		timer.cancel();
		if (cancelled == false) {
			MessageManager.get().addMessage(new Message("Pushing cancelled", Priority.ERROR, null));// LOC
			cancelled = true;
		}
	}

	@Override
	public void openConnection() {
		timer.scheduleRepeating(UpdaterConstants.KEEPALIVE_INTERVAL_SECONDS * 1000);
	}

	private class KeepaliveTimer extends Timer {
		@Override
		public void run() {
			FreenetRequest.sendRequest(UpdaterConstants.keepalivePath, new QueryParameter("requestId", FreenetJs.requestId), new RequestCallback() {

				@Override
				public void onResponseReceived(Request request, Response response) {
					if (response.getText().compareTo(UpdaterConstants.SUCCESS) != 0) {
						closeConnection();
					}
				}

				@Override
				public void onError(Request request, Throwable exception) {
					closeConnection();
				}
			});
		}
	}
}
