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

	/** Debug mode. If true, the client will log. Should be false at production */
	public static boolean				isDebug						= true;

	/** The requestId. It is used to identify this instance to the server */
	public static String				requestId;

	/** The manager */
	private static IConnectionManager	cm;

	/** The keepalive manager */
	private static IConnectionManager	keepaliveManager;

	/** If true, then pushing cancel is expected, so it won't show a message for it. */
	public static boolean				isPushingCancelledExpected	= false;

	public void onModuleLoad() {
		// If the user closes the window, it sends a leaving message
		Window.addWindowClosingHandler(new ClosingHandler() {
			@Override
			public void onWindowClosing(ClosingEvent event) {
				isPushingCancelledExpected = true;
				FreenetRequest.sendRequest(UpdaterConstants.leavingPath, new QueryParameter("requestId", requestId));
				cm.closeConnection();
			}
		});
		// Exports some method for external use
		// It is not needed, but may come handy in the future
		exportStaticMethod();

		requestId = RootPanel.get("requestId").getElement().getAttribute("value");
		cm = new SharedConnectionManager(new DefaultUpdateManager());
		keepaliveManager = new KeepaliveManager();
		cm.openConnection();
		keepaliveManager.openConnection();
		new TimeIncrementer().start();
		// Create the MessageManager object to let it register it's listener
		MessageManager.get();
	}

	static long logCounter;
	
	/** Log a message */
	public static final void log(String msg) {
		try {
			// Only log id debug is enabled
			if (isDebug) {
				// Write the log back to the server
				 try{ FreenetRequest.sendRequest(UpdaterConstants.logWritebackPath, new QueryParameter("msg",requestId+":"+(logCounter++)+":"+urlEncode(msg))); }catch(Exception e){
				 
				 }
				// Write the log to the console
				nativeLog(msg);
				// Write the log to the page
				/*
				 * Panel logPanel = RootPanel.get("log"); if (logPanel == null) { logPanel = new SimplePanel(); logPanel.getElement().setId("log");
				 * logPanel.getElement().setAttribute("style", "display:none;"); Document.get().getElementsByTagName("body").getItem(0).appendChild(logPanel.getElement()); }
				 * logPanel.add(new Label("{" + System.currentTimeMillis() + "}" + msg));
				 */
			}
		} catch (Exception e) {
			// If an error occurs, we suppress it. Logging should not throw exceptions
		}
	}

	/** Base 64 causes some bizarre data corruption, probably because / and + are not allowed in URLs.
	  * Java's URLEncoder isn't available, and Freenet's URLEncoder doesn't compile: getBytes() doesn't work.
	  * So hack together a pathetic feature incomplete encoder that doesn't use getBytes(). 
	  * REDFLAG: THIS IS NOT REMOTELY SAFE!!!! */
	private static String urlEncode(String s) {
		StringBuffer sb = new StringBuffer(s.length());
		for(int i=0;i<s.length();i++) {
			char c = s.charAt(i);
			if(c == '%') {
				sb.append("%25");
			} else if(c == '?') {
				sb.append("%3f");
			} else if(c == '&') {
				sb.append("%26");
			} else if(c == '#') {
				sb.append("%23");
			} else if(c == '/') {
				sb.append("%2f");
			} else if(c == '=') {
				sb.append("%3d");
			} else if(c == ':') {
				sb.append("%3a");
			} else if(c == ';') {
				sb.append("%3b");
			} else sb.append(c);
		}
		return sb.toString();
	}
	
	/** Exported method to let external sources turn on logging */
	public static final void enableDebug() {
		isDebug = true;
	}

	/** Logs a message to the console */
	public static final native void nativeLog(String msg) /*-{
															console.log(msg);
															}-*/;

	/** Exports some methods */
	public static native void exportStaticMethod() /*-{
													$wnd.log =
													@freenet.client.FreenetJs::log(Ljava/lang/String;);
													$wnd.enableDebug =
													@freenet.client.FreenetJs::enableDebug();
													}-*/;

	/** Stops the pushing, closing connections */
	public static void stop() {
		cm.closeConnection();
		keepaliveManager.closeConnection();
	}

}
