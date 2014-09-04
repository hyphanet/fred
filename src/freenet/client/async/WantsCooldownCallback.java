package freenet.client.async;

/** Interface for requests that require a callback when they go into cooldown.
 * Called object should schedule a job on the jobRunner if it is persistent and will change things. */
public interface WantsCooldownCallback {

	/** The request has gone into cooldown for some period. */
	void enterCooldown(ClientGetState state, long wakeupTime, ClientContext context);

	/** The request has unexpectedly left cooldown. */
	void clearCooldown(ClientGetState state);

}
