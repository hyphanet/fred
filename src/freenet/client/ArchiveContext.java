/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.util.HashSet;
import freenet.keys.FreenetURI;

/**
 * @author amphibian (Matthew Toseland)
 * Object passed down a full fetch, including all the recursion.
 * Used, at present, for detecting archive fetch loops, hence the
 * name.
 */
public class ArchiveContext {

	HashSet soFar = new HashSet();
	final int maxArchiveLevels;
	
	public ArchiveContext(int max) {
		this.maxArchiveLevels = max;
	}
	
	/**
	 * Check for a loop.
	 * The URI provided is expected to be a reasonably unique identifier for the archive.
	 */
	public synchronized void doLoopDetection(FreenetURI key) throws ArchiveFailureException {
		if(soFar.size() > maxArchiveLevels)
			throw new ArchiveFailureException(ArchiveFailureException.TOO_MANY_LEVELS);
	}

}
