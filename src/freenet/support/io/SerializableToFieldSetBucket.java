/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 *
 * @author unknown
 */
public interface SerializableToFieldSetBucket extends Bucket {

	/**
	 *
	 * @return
	 */
	public SimpleFieldSet toFieldSet();

}
