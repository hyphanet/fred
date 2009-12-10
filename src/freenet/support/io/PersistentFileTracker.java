/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;

/**
 *
 * @author unknown
 */
public interface PersistentFileTracker {

	/**
	 *
	 * @param file
	 */
	public void register(File file);

	/** Notify that we have finished with a bucket and it should be freed after the
	 * next serialization to disk.
	 * @param bucket The bucket to free. Should be a DelayedFreeBucket.
	 */
	public void delayedFreeBucket(DelayedFreeBucket bucket);

	/**
	 * Get the persistent temp files directory.
	 *
	 * @return
	 */
	public File getDir();

	/**
	 * Is the file in question one of our persistent temp files?
	 *
	 * @param file
	 * @return
	 */
	public boolean matches(File file);

	/**
	 *
	 * @return
	 */
	public FilenameGenerator getGenerator();

	/**
	 *
	 * @param file
	 * @return
	 */
	public long getID(File file);

}
