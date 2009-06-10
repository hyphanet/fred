package freenet.client.update;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.FreenetJs;
import freenet.client.connection.IConnectionManager;
import freenet.client.tools.Base64;

public class DefaultUpdateManager implements IUpdateManager {
	public final String		requestId;

	public static final String	SEPARATOR	= ":";

	public DefaultUpdateManager(String requestId) {
		this.requestId = requestId;
	}

	@Override
	public void updated(String message) {
		String elementId = message;
		FreenetJs.log("elementiddecoded:" + elementId);
		try {
			new RequestBuilder(RequestBuilder.GET, IConnectionManager.dataPath+"?requestId="+requestId+"&elementId="+elementId).sendRequest(null, new UpdaterRequestCallback(elementId));
		} catch (RequestException re) {
			FreenetJs.log("EXCEPTION at DefaultUpdateManager.updated!");
		}
	}
	
	private class UpdaterRequestCallback implements RequestCallback{
		
		private final String elementId;
		
		private UpdaterRequestCallback(String elementId){
			this.elementId=elementId;
		}
		@Override
		public void onResponseReceived(Request request, Response response) {
			if(response.getText().startsWith("SUCCESS")==false){
				FreenetJs.log("ERROR! BAD DATA");
				FreenetJs.stop();
			}else{
				String newContent=Base64.decode(response.getText().substring("SUCCESS:".length()));
				RootPanel.get(elementId).getElement().setInnerHTML(newContent);
			}
		}
		
		@Override
		public void onError(Request request, Throwable exception) {
			FreenetJs.log("ERROR! AT DATA GETTING!");
		}

	}

}
