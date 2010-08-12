package freenet.client.async;

/** Something that might have a CooldownTrackerItem associated with it. We don't just use
 * SendableGet because e.g. SplitFileFetcherSegment isn't one.
 * @author toad
 */
public interface HasCooldownTrackerItem {

	/** Construct a new CooldownTrackerItem. Called inside CooldownTracker lock. */
	CooldownTrackerItem makeCooldownTrackerItem();

}
