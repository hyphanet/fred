/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import freenet.keys.FreenetURI;

/** Simple interface for a splitfile block */
public interface StartableSplitfileBlock extends SplitfileBlock {

	/** Start the fetch (or insert). Implementation is required to call relevant
	 * methods on RetryTracker when done. */
	abstract void start();

	/**
	 * Shut down the fetch as soon as reasonably possible.
	 */
	abstract public void kill();

	abstract public int getRetryCount();
	
	/**
	 * Get the URI of the file. For an insert, this is derived during insert.
	 * For a request, it is fixed in the constructor.
	 */
	abstract public FreenetURI getURI();

}
