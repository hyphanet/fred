package freenet.client.async;

import com.db4o.ObjectContainer;

/** Interface for requests that require a callback when they go into cooldown.
 * Called object should schedule a job on the jobRunner if it is persistent and will change things. */
public interface WantsCooldownCallback {

	/** The request has gone into cooldown for some period. */
	void enterCooldown(long wakeupTime, ObjectContainer container, ClientContext context);

	/** The request has unexpectedly left cooldown. */
	void clearCooldown(ObjectContainer container);

}
