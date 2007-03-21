/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.RandomGrabArray;

public interface RequestScheduler {

	public SendableRequest removeFirst();

	/** Tell the scheduler that a request from a specific RandomGrabArray succeeded.
	 * Definition of "succeeded" will vary, but the point is most schedulers will run another
	 * request from the parentGrabArray in the near future on the theory that if one works,
	 * another may also work. 
	 * */
	public void succeeded(RandomGrabArray parentGrabArray);
	
}
