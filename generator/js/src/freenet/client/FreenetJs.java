package freenet.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.Window.ClosingHandler;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.connection.IConnectionManager;
import freenet.client.connection.KeepaliveManager;
import freenet.client.connection.SharedConnectionManager;
import freenet.client.dynamics.TimeIncrementer;
import freenet.client.messages.MessageManager;
import freenet.client.tools.FreenetRequest;
import freenet.client.tools.QueryParameter;
import freenet.client.update.DefaultUpdateManager;

/**
 * Entry point classes define <code>onModuleLoad()</code>.
 */
public class FreenetJs implements EntryPoint {

	public static boolean				isDebug						= true;

	public static String				requestId;

	private static IConnectionManager	cm;

	private static IConnectionManager	keepaliveManager;

	public static boolean				isPushingCancelledExpected	= false;

	public void onModuleLoad() {
		Window.addWindowClosingHandler(new ClosingHandler() {
			@Override
			public void onWindowClosing(ClosingEvent event) {
				isPushingCancelledExpected = true;
				FreenetRequest.sendRequest(UpdaterConstants.leavingPath, new QueryParameter("requestId", requestId));
				cm.closeConnection();
			}
		});
		exportStaticMethod();
		requestId = RootPanel.get("requestId").getElement().getAttribute("value");
		cm = new SharedConnectionManager(new DefaultUpdateManager());
		keepaliveManager = new KeepaliveManager();
		cm.openConnection();
		keepaliveManager.openConnection();
		new TimeIncrementer().start();
		MessageManager.get();
	}

	public static final void log(String msg) {
		try {
			if (isDebug) {
				/*
				 * try{ FreenetRequest.sendRequest(UpdaterConstants.logWritebackPath, new QueryParameter("msg",URL.encode(msg))); }catch(Exception e){
				 * 
				 * }
				 */
				nativeLog(msg);
				/*
				 * Panel logPanel = RootPanel.get("log"); if (logPanel == null) { logPanel = new SimplePanel(); logPanel.getElement().setId("log");
				 * logPanel.getElement().setAttribute("style", "display:none;"); Document.get().getElementsByTagName("body").getItem(0).appendChild(logPanel.getElement()); }
				 * logPanel.add(new Label("{" + System.currentTimeMillis() + "}" + msg));
				 */
			}
		} catch (Exception e) {

		}
	}

	public static final void enableDebug() {
		isDebug = true;
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
