package freenet.client.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;
import freenet.client.connection.IConnectionManager;
import freenet.client.tools.Base64;
import freenet.client.tools.FreenetRequest;
import freenet.client.tools.QueryParameter;
import freenet.client.updaters.IUpdater;
import freenet.client.updaters.ImageElementUpdater;
import freenet.client.updaters.ProgressBarUpdater;

public class DefaultUpdateManager implements IUpdateManager {
	public static final String					SEPARATOR	= ":";

	private static final Map<String, IUpdater>	updaters;

	private static final List<UpdateListener>	listeners	= new ArrayList<UpdateListener>();
	
	static {
		Map<String, IUpdater> list = new HashMap<String, IUpdater>();
		list.put(UpdaterConstants.PROGRESSBAR_UPDATER, new ProgressBarUpdater());
		list.put(UpdaterConstants.IMAGE_ELEMENT_UPDATER, new ImageElementUpdater());
		updaters = Collections.unmodifiableMap(list);
	}


	public static void registerListener(UpdateListener listener) {
		listeners.add(listener);
	}

	public static void deregisterListener(UpdateListener listener) {
		listeners.remove(listener);
	}

	@Override
	public void updated(String message) {
		String elementId = message;
		FreenetJs.log("DefaultUpdateManager updated:elementid:" + elementId);
		FreenetRequest.sendRequest(IConnectionManager.dataPath, new QueryParameter[] { new QueryParameter("requestId", FreenetJs.requestId),
				new QueryParameter("elementId", elementId) }, new UpdaterRequestCallback(elementId));
		for (UpdateListener l : listeners) {
			l.onUpdate();
		}
	}

	private class UpdaterRequestCallback implements RequestCallback {

		private final String	elementId;

		private UpdaterRequestCallback(String elementId) {
			this.elementId = elementId;
		}

		@Override
		public void onResponseReceived(Request request, Response response) {
			FreenetJs.log("Data received");
			if (response.getText().startsWith("SUCCESS") == false) {
				FreenetJs.log("ERROR! BAD DATA");
				FreenetJs.stop();
			} else {
				String updaterType = Base64.decode(response.getText().split("[:]")[1]);
				String newContent = Base64.decode(response.getText().split("[:]")[2]);
				FreenetJs.log("Element will be updated with type:" + updaterType + " and content:" + newContent);
				updaters.get(updaterType).updated(elementId, newContent);
			}
		}

		@Override
		public void onError(Request request, Throwable exception) {
			FreenetJs.log("ERROR! AT DATA GETTING!");
		}

	}

}
