/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import freenet.client.async.ClientContext;
import freenet.client.async.RequestSelectionTreeNode;

public interface RandomGrabArrayItemExclusionList {
	
	/** Can this item be excluded because of the cooldown queue, without activating it? 
	 * @return The time at which the item should have valid requests, or -1 if it is 
	 * valid already. */
	public long excludeSummarily(RequestSelectionTreeNode item, RequestSelectionTreeNode parent, long now);
	
	/**
	 * Whether this item can be returned right now.
	 */
	public long exclude(RandomGrabArrayItem item, ClientContext context, long now);

}
