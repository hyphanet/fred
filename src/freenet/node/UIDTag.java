package freenet.node;

/**
 * Base class for tags representing a running request. These store enough information
 * to detect whether they are finished; if they are still in the list, this normally
 * represents a bug.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public abstract class UIDTag {
	
	final long createdTime;
	
	UIDTag() {
		createdTime = System.currentTimeMillis();
	}

	public abstract void logStillPresent(Long uid);

	long age() {
		return System.currentTimeMillis() - createdTime;
	}

}
