package freenet.client.async;

/** An element in the in-memory-only cooldown queue. Tracks retry counts and cooldown 
 * times for a request which has maxRetries = -1 and therefore does not need to store them
 * persistently.
 * @author toad
 */
public interface CooldownTrackerItem {

}
