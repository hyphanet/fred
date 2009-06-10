package freenet.client.connection;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Timer;

import freenet.client.update.IUpdateManager;

public class LongPollingConnectionManager implements IConnectionManager {

	private IUpdateManager	updateManager;

	private int				numOfFailedRequests	= 0;

	private Request			sentRequest			= null;

	private boolean			running				= false;

	
	public LongPollingConnectionManager(IUpdateManager updateManager){
		this.updateManager=updateManager;
	}
	@Override
	public void closeConnection() {
		running = false;
		if (sentRequest != null && sentRequest.isPending()) {
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

	private void scheduleNextRequest() {
		new Timer() {
			@Override
			public void run() {
				sendRequest();
			}
		}.schedule(Math.min((int) Math.pow(2, (numOfFailedRequests++)), 10000));
	}

	private void sendRequest() {
		if (running == true) {
			try {
				sentRequest = new RequestBuilder(RequestBuilder.GET, IConnectionManager.path).sendRequest(null, new RequestCallback() {

					@Override
					public void onResponseReceived(Request request, Response response) {
						if (response.getText().startsWith("SUCCESS:")) {
							numOfFailedRequests = 0;
							updateManager.updated(response.getText().substring("SUCCESS:".length()));
						}
						scheduleNextRequest();
					}

					@Override
					public void onError(Request request, Throwable exception) {
						scheduleNextRequest();
					}
				});
			} catch (RequestException e) {
				scheduleNextRequest();
			}
		}
	}

}
