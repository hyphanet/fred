package freenet.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;

/**
 * Entry point classes define <code>onModuleLoad()</code>.
 */
public class FreenetJs implements EntryPoint {
	Label div=new Label();
	public void onModuleLoad() {
		div.setText("HAHO2!");
		RootPanel.get("page").add(div);
		try {
			new RequestBuilder(RequestBuilder.GET, "/pushdata/").sendRequest("", new RequestCallback(){
			
				@Override
				public void onResponseReceived(Request request, Response response) {
					div.setText(response.getText());
				}
			
				@Override
				public void onError(Request request, Throwable exception) {
					log("ERROR");
				}
			});
		} catch (RequestException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	  public final native void log(String msg) /*-{
	     console.log(msg);
	   }-*/;
	
}
