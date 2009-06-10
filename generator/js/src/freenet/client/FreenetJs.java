package freenet.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.connection.SharedConnectionManager;
import freenet.client.update.DefaultUpdateManager;

/**
 * Entry point classes define <code>onModuleLoad()</code>.
 */
public class FreenetJs implements EntryPoint {
	Label	div	= new Label();

	public void onModuleLoad() {
		div.getElement().setId("abc");
		RootPanel.get("page").add(div);
		SharedConnectionManager cm = new SharedConnectionManager(new DefaultUpdateManager());
		cm.openConnection();
	}

	public static final native void log(String msg) /*-{
													console.log(msg);
													}-*/;

}
