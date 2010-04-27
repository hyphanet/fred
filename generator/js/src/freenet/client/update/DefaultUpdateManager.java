package freenet.client.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.Response;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;
import freenet.client.tools.Base64;
import freenet.client.tools.FreenetRequest;
import freenet.client.tools.QueryParameter;
import freenet.client.updaters.ConnectionsListUpdater;
import freenet.client.updaters.IUpdater;
import freenet.client.updaters.ImageElementUpdater;
import freenet.client.updaters.ProgressBarUpdater;
import freenet.client.updaters.ReplacerUpdater;
import freenet.client.updaters.XmlAlertUpdater;

/** This UpdateManager provides the default pushing functionality */
public class DefaultUpdateManager implements IUpdateManager {

	/** The registered Updater that will be used to update different elements */
	private static final Map<String, IUpdater>	updaters;

	/** The listeners that will be notified when update occurs */
	private static final List<IUpdateListener>	listeners	= new ArrayList<IUpdateListener>();

	// Initializes the updaters
	static {
		Map<String, IUpdater> list = new HashMap<String, IUpdater>();
		list.put(UpdaterConstants.PROGRESSBAR_UPDATER, new ProgressBarUpdater());
		list.put(UpdaterConstants.IMAGE_ELEMENT_UPDATER, new ImageElementUpdater());
		list.put(UpdaterConstants.REPLACER_UPDATER, new ReplacerUpdater());
		list.put(UpdaterConstants.CONNECTIONS_TABLE_UPDATER, new ConnectionsListUpdater());
		list.put(UpdaterConstants.XMLALERT_UPDATER, new XmlAlertUpdater());
		updaters = Collections.unmodifiableMap(list);
	}

	/**
	 * registers a listener that will be notified when update occurs
	 * 
	 * @param listener
	 *            - The listener to be registered
	 */
	public static void registerListener(IUpdateListener listener) {
		listeners.add(listener);
	}

	/**
	 * Removes a listener
	 * 
	 * @param listener
	 *            - The listener to be removed
	 */
	public static void deregisterListener(IUpdateListener listener) {
		listeners.remove(listener);
	}

	@Override
	public void updated(String message) {
		// Identifies the element
		String elementId = message;
		FreenetJs.log("DefaultUpdateManager updated:elementid:" + elementId);
		// Sends a request asking for data for the updated element
		FreenetRequest.sendRequest(UpdaterConstants.dataPath, new QueryParameter[] { new QueryParameter("requestId", FreenetJs.requestId),
				new QueryParameter("elementId", elementId) }, new UpdaterRequestCallback(elementId));
		// Notifies the listeners
		for (IUpdateListener l : listeners) {
			l.onUpdate();
		}
	}

	/** A request callback that handles the response for element data */
	private class UpdaterRequestCallback implements RequestCallback {

		/** The element's id that is updating */
		private final String	elementId;

		private UpdaterRequestCallback(String elementId) {
			this.elementId = elementId;
		}

		@Override
		public void onResponseReceived(Request request, Response response) {
			FreenetJs.log("Data received");
			if (response.getText().startsWith("SUCCESS") == false) {
				// If something bad happened, we stop the pushing
				FreenetJs.log("ERROR! BAD DATA");
				FreenetJs.stop();
			} else {
				// The Updater type
				String updaterType = Base64.decode(response.getText().split("[:]")[1]);
				// The new content
				String newContent = Base64.decode(response.getText().split("[:]")[2]);
				FreenetJs.log("Element "+elementId+" will be updated with type:" + updaterType + " and content:" + newContent);
				// Update the element with the given updater with the got content
				updaters.get(updaterType).updated(elementId, newContent);
			}
		}

		@Override
		public void onError(Request request, Throwable exception) {
			FreenetJs.log("ERROR! AT DATA GETTING!");
		}

	}

}
