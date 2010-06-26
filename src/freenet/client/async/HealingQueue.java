/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.support.api.Bucket;

public interface HealingQueue {

	/** Queue a Bucket of data to insert as a CHK. */
	void queue(Bucket data, byte[] cryptoKey, byte cryptoAlgorithm, ClientContext context);

}
