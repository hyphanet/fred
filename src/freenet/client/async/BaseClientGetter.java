/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.node.RequestClient;

public abstract class BaseClientGetter extends ClientRequester implements
		GetCompletionCallback {
	
	protected BaseClientGetter(short priorityClass, RequestClient client) {
		super(priorityClass, client);
	}

}
