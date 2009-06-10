package freenet.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.connection.IConnectionManager;
import freenet.client.connection.SharedConnectionManager;
import freenet.client.update.DefaultUpdateManager;

/**
 * Entry point classes define <code>onModuleLoad()</code>.
 */
public class FreenetJs implements EntryPoint {

	private static IConnectionManager cm;
	
	public void onModuleLoad() {
		
		String requestId=RootPanel.get("requestId").getElement().getAttribute("value");
		cm = new SharedConnectionManager(new DefaultUpdateManager(requestId));
		cm.openConnection();
	}

	public static final native void log(String msg) /*-{
													console.log(msg);
													}-*/;
	
	public static void stop(){
		cm.closeConnection();
	}

}
