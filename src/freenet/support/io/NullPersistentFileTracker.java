/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;

/**
 *
 * @author unknown
 */
public class NullPersistentFileTracker implements PersistentFileTracker {

	private static NullPersistentFileTracker instance;

	/**
	 *
	 * @return
	 */
	public static synchronized NullPersistentFileTracker getInstance() {
		if(instance == null) {
			instance = new NullPersistentFileTracker();
		}
		return instance;
	}

	private NullPersistentFileTracker() {
	}

	/**
	 *
	 * @param file
	 */
	public void register(File file) {
		// Do nothing
	}

	public void delayedFreeBucket(DelayedFreeBucket bucket) {
		// Free immediately
		bucket.free();
	}

	public File getDir() {
		return new File(".");
	}

	public boolean matches(File file) {
		return false;
	}

	/**
	 * 
	 * @return
	 */
	public FilenameGenerator getGenerator() {
		return null;
	}

	/**
	 *
	 * @param file
	 * @return
	 */
	public long getID(File file) {
		return 0;
	}

}
