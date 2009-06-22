package freenet.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.SimplePanel;

import freenet.client.connection.IConnectionManager;
import freenet.client.connection.KeepaliveManager;
import freenet.client.connection.SharedConnectionManager;
import freenet.client.update.DefaultUpdateManager;

/**
 * Entry point classes define <code>onModuleLoad()</code>.
 */
public class FreenetJs implements EntryPoint {

	public static boolean			isDebug	= false;

	public static String				requestId;

	private static IConnectionManager	cm;

	private static IConnectionManager	keepaliveManager;

	public void onModuleLoad() {
		exportStaticMethod();
		requestId = RootPanel.get("requestId").getElement().getAttribute("value");
		cm = new SharedConnectionManager(new DefaultUpdateManager());
		keepaliveManager = new KeepaliveManager();
		cm.openConnection();
		keepaliveManager.openConnection();

	}

	public static final void log(String msg) {
		if (isDebug) {
			//nativeLog(msg);
			Panel logPanel = RootPanel.get("log");
			if (logPanel == null) {
				logPanel = new SimplePanel();
				logPanel.getElement().setId("log");
				RootPanel.get("content").add(logPanel);
			}
			logPanel.add(new Label("{"+System.currentTimeMillis()+"}"+msg));
		}
	}
	
	public static final void enableDebug(){
		isDebug=true;
	}

	public static final native void nativeLog(String msg) /*-{
															console.log(msg);
															}-*/;

	public static native void exportStaticMethod() /*-{
													$wnd.log =
													@freenet.client.FreenetJs::log(Ljava/lang/String;);
													$wnd.enableDebug =
													@freenet.client.FreenetJs::enableDebug();
													}-*/;

	public static void stop() {
		cm.closeConnection();
		keepaliveManager.closeConnection();
	}

}
