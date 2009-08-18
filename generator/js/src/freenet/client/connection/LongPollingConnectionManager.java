package freenet.client.connection;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Timer;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;
import freenet.client.tools.FreenetRequest;
import freenet.client.tools.QueryParameter;
import freenet.client.update.IUpdateManager;

/** This ConnectionManager manages a long polling connection, that keeps a connection open at all times */
public class LongPollingConnectionManager implements IConnectionManager {

	/** The UpdateManager that gets notified when data is received */
	private IUpdateManager	updateManager;

	/** The number of failed requests after the last successful one */
	private int				numOfFailedRequests	= 0;

	/** The last sent request */
	private Request			sentRequest			= null;

	/** Is running? */
	private boolean			running				= false;

	public LongPollingConnectionManager(IUpdateManager updateManager) {
		this.updateManager = updateManager;
	}

	@Override
	public void closeConnection() {
		running = false;
		if (sentRequest != null && sentRequest.isPending()) {
			// If there is a connection open, then closes it
			sentRequest.cancel();
		}
	}

	@Override
	public void openConnection() {
		if (updateManager == null) {
			throw new RuntimeException("You must set the UpdateManager before opening the connection!");
		}
		running = true;
		sendRequest();
	}

	/** Schedules the next request. It waits more and more as more requests fails, but will try forever. */
	private void scheduleNextRequest() {
		new Timer() {
			@Override
			public void run() {
				// When run, send a request
				sendRequest();
			}
		}.schedule(Math.max(Math.min((int) Math.pow(2, (numOfFailedRequests++)), 10000),50));// Waits more if requests failing, but a max at 10sec
		FreenetJs.log("Next request scheduled");
	}

	/** Sends a request */
	private void sendRequest() {
		// Only send if running
		if (running == true) {
			sentRequest = FreenetRequest.sendRequest(UpdaterConstants.notificationPath, new QueryParameter("requestId", FreenetJs.requestId), new RequestCallback() {
				@Override
				public void onResponseReceived(Request request, Response response) {
					FreenetJs.log("AJAX response:success:" + (response.getText().startsWith(UpdaterConstants.SUCCESS) ? "true" : "false"));
					if (response.getText().startsWith(UpdaterConstants.SUCCESS)) {
						// If success, then notify the UpdateManager
						numOfFailedRequests = 0;
						updateManager.updated(response.getText().substring("SUCCESS:".length()));
					}
					if (response.getText().startsWith(UpdaterConstants.FAILURE)) {
						// If failure, then there are no pushed elements on the page, so the stopping is expected
						FreenetJs.isPushingCancelledExpected = true;
						FreenetJs.stop();
					}
					// Schedules the next request
					scheduleNextRequest();
				}

				@Override
				public void onError(Request request, Throwable exception) {
					// If errorous, then try again
					FreenetJs.log("AJAX response:ERROR");
					scheduleNextRequest();
				}
			});
		}
	}

}
