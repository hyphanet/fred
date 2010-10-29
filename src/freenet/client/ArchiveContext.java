/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.util.HashSet;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;

/**
 * @author amphibian (Matthew Toseland)
 *
 * Object passed down a full fetch, including all the recursion.
 * Used, at present, for detecting archive fetch loops, hence the
 * name.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class ArchiveContext {

	private HashSet soFar = new HashSet();
	final int maxArchiveLevels;
	final long maxArchiveSize;
	
	public ArchiveContext(long maxArchiveSize, int max) {
		this.maxArchiveLevels = max;
		this.maxArchiveSize = maxArchiveSize;
	}
	
	/**
	 * Check for a loop.
	 *
	 * The URI provided is expected to be a reasonably unique identifier for the archive.
	 */
	public synchronized void doLoopDetection(FreenetURI key, ObjectContainer container) throws ArchiveFailureException {
		if(container != null)
			container.activate(soFar, Integer.MAX_VALUE);
		if(soFar.size() > maxArchiveLevels)
			throw new ArchiveFailureException(ArchiveFailureException.TOO_MANY_LEVELS);
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(soFar);
		container.delete(this);
	}
}
