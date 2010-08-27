package freenet.node;

/**
 * Base class for tags representing a running request. These store enough information
 * to detect whether they are finished; if they are still in the list, this normally
 * represents a bug.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public abstract class UIDTag {
	
	final long createdTime;
	// FIXME weak reference? purge on drop?
	// weak reference has the disadvantage that if it's cleared it would be counted as local?
	// Maybe we could compare to the local vs remote on the subclass?
	// in theory when disconnect we will remove it anyway, so i guess it's not a big deal?
	final PeerNode source;
	
	UIDTag(PeerNode source) {
		createdTime = System.currentTimeMillis();
		this.source = source;
	}

	public abstract void logStillPresent(Long uid);

	long age() {
		return System.currentTimeMillis() - createdTime;
	}

}
