/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;

import freenet.support.api.Bucket;

public class NullPersistentFileTracker implements PersistentFileTracker {

	public void register(File file) {
		// Do nothing
	}

	public void delayedFreeBucket(Bucket bucket) {
		// Free immediately
		bucket.free();
	}

}
