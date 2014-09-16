package freenet.node;

/** Lightweight key for tracking which SendableRequestItem's are running. Does not need to include
 * expensive stuff such as the data to insert. Used by e.g. KeysFetchingLocally to track what 
 * transient inserts are running. @see SendableRequestItem.getKey(). If that method just returns 
 * the request, the default equals() and hashCode() should be fine, however if you have a separate
 * key class for checking quickly whether something is queued you will need real equals() and 
 * hashCode() methods. Should be globally unique, i.e. should not equal the keys for other 
 * requests, so don't just use e.g. an integer on its own. */
public interface SendableRequestItemKey {
	
	/** You must implement this! */
	@Override
	public boolean equals(Object o);
	
	@Override
	/** You must implement this! */
	public int hashCode();

}
