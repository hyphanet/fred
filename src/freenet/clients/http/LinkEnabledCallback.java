package freenet.clients.http;

public interface LinkEnabledCallback {

	/** Whether to show the link? 
	 * @param ctx The request which is asking. Can be null. */
	boolean isEnabled(ToadletContext ctx);

}
