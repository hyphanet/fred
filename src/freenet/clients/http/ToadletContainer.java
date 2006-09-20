package freenet.clients.http;

import java.net.URI;

/** Interface for toadlet containers. Toadlets should register here. */
public interface ToadletContainer {
	
	/** Register a Toadlet. All requests whose URL starts with the given
	 * prefix will be passed to this toadlet.
	 * @param atFront If true, add to front of list (where is checked first),
	 * else add to back of list (where is checked last).
	 */
	public void register(Toadlet t, String urlPrefix, boolean atFront);

	/**
	 * Find a Toadlet by URI.
	 */
	public Toadlet findToadlet(URI uri);
	
	/**
	 * Get the name of the theme to be used by all the Toadlets
	 */
	public String getCSSName();
}
