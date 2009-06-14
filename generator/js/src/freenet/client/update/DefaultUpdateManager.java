package freenet.client.update;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;
import freenet.client.connection.IConnectionManager;
import freenet.client.tools.Base64;
import freenet.client.updaters.IUpdater;
import freenet.client.updaters.ProgressBarUpdater;

public class DefaultUpdateManager implements IUpdateManager {
	public static final String					SEPARATOR	= ":";

	private static final Map<String, IUpdater>	updaters;
	static {
		Map<String, IUpdater> list = new HashMap<String, IUpdater>();
		list.put(UpdaterConstants.PROGRESSBAR_UPDATER, new ProgressBarUpdater());
		updaters = Collections.unmodifiableMap(list);
	}

	public DefaultUpdateManager() {
	}

	@Override
	public void updated(String message) {
		String elementId = message;
		FreenetJs.log("elementiddecoded:" + elementId);
		try {
			new RequestBuilder(RequestBuilder.GET, IConnectionManager.dataPath + "?requestId=" + FreenetJs.requestId + "&elementId=" + elementId).sendRequest(null, new UpdaterRequestCallback(elementId));
		} catch (RequestException re) {
			FreenetJs.log("EXCEPTION at DefaultUpdateManager.updated!");
		}
	}

	private class UpdaterRequestCallback implements RequestCallback {

		private final String	elementId;

		private UpdaterRequestCallback(String elementId) {
			this.elementId = elementId;
		}

		@Override
		public void onResponseReceived(Request request, Response response) {
			if (response.getText().startsWith("SUCCESS") == false) {
				FreenetJs.log("ERROR! BAD DATA");
				FreenetJs.stop();
			} else {
				String updaterType = Base64.decode(response.getText().split("[:]")[1]);
				String newContent = Base64.decode(response.getText().split("[:]")[2]);
				updaters.get(updaterType).updated(elementId, newContent);
			}
		}

		@Override
		public void onError(Request request, Throwable exception) {
			FreenetJs.log("ERROR! AT DATA GETTING!");
		}

	}

}
