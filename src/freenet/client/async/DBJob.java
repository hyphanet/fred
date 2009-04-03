/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

/**
 * A job to be run on the database thread. We will pass a transactional context in,
 * and a ClientContext.
 * @author toad
 */
public interface DBJob {
	
	void run(ObjectContainer container, ClientContext context);

}
