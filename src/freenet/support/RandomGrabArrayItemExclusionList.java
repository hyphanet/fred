/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

public interface RandomGrabArrayItemExclusionList {
	
	/**
	 * Whether this item can be returned right now.
	 */
	public boolean exclude(RandomGrabArrayItem item, ObjectContainer container, ClientContext context);

}
