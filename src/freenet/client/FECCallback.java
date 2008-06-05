/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

/**
 * An interface wich has to be implemented by FECJob submitters
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 * 
 * WARNING: the callback is expected to release the thread !
 */
public interface FECCallback {

	public void onEncodedSegment(ObjectContainer container, ClientContext context);

	public void onDecodedSegment(ObjectContainer container, ClientContext context);
}