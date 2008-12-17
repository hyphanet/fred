package freenet.clients.http;

public interface LinkEnabledCallback {

	/** Whether to show the link? 
	 * @param ctx */
	boolean isEnabled(ToadletContext ctx);

}
