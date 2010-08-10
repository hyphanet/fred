/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.HasCooldownCacheItem;

public interface RandomGrabArrayItemExclusionList {
	
	/** Can this item be excluded because of the cooldown queue, without activating it? 
	 * @return The time at which the item should have valid requests, or 0 if it is 
	 * valid already. */
	public long excludeSummarily(HasCooldownCacheItem item, HasCooldownCacheItem parent, ObjectContainer container, boolean persistent, long now);
	
	/**
	 * Whether this item can be returned right now.
	 */
	public boolean exclude(RandomGrabArrayItem item, ObjectContainer container, ClientContext context);

}
