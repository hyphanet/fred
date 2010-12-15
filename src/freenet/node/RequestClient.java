/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

/**
 * Must be implemented by any client object returned by SendableRequest.getClient().
 * Mostly this is for scheduling, but it does have one key purpose: to identify whether
 * a request is persistent or not.
 * @author toad
 */
public interface RequestClient {
	
	/**
	 * Is this request persistent? **Must not change!**
	 */
	public boolean persistent();
	
	/**
	 * Is this request real-time? True = low latency, false = high throughput.
	 * **Must not change!**
	 */
	public boolean realTimeFlag();

	public void removeFrom(ObjectContainer container);

}
