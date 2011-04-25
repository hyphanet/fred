package freenet.client.async;

public interface USKProgressCallback extends USKCallback {
	
	/** Called when the datastore has been checked and we are now going to send
	 * requests. There might be a long gap between this callback and the time
	 * at which we actually start a network level request. */
	public void onSendingToNetwork(ClientContext context);
	
	/** Called when we have checked the datastore, and all our polling requests
	 * have gone into cooldown, and all our random future edition probes have
	 * completed. */
	public void onRoundFinished(ClientContext context);

}
