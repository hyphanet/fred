package freenet.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.connection.IConnectionManager;
import freenet.client.connection.KeepaliveManager;
import freenet.client.connection.SharedConnectionManager;
import freenet.client.update.DefaultUpdateManager;

/**
 * Entry point classes define <code>onModuleLoad()</code>.
 */
public class FreenetJs implements EntryPoint {

	public static final boolean			isDebug	= false;

	public static String				requestId;

	private static IConnectionManager	cm;

	private static IConnectionManager	keepaliveManager;

	public void onModuleLoad() {

		requestId = RootPanel.get("requestId").getElement().getAttribute("value");
		cm = new SharedConnectionManager(new DefaultUpdateManager());
		keepaliveManager = new KeepaliveManager();
		cm.openConnection();
		keepaliveManager.openConnection();

	}

	public static final void log(String msg) {
		if (isDebug) {
			nativeLog(msg);
		}
	}

	public static final native void nativeLog(String msg) /*-{
															console.log(msg);
															}-*/;

	public static void stop() {
		cm.closeConnection();
		keepaliveManager.closeConnection();
	}

}
