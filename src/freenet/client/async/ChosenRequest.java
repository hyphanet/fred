/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.NodeClientCore;
import freenet.node.RequestScheduler;
import freenet.node.SendableRequest;

/**
 * A request chosen by ClientRequestScheduler.
 * @author toad
 */
public class ChosenRequest {

	/** The request object */
	public final SendableRequest request;
	/** The token indicating the key within the request to be fetched/inserted.
	 * Meaning is entirely defined by the request. */
	public final Object token;
	/** The key to be fetched, null if not a BaseSendableGet */
	public final Key key;
	/** The client-layer key to be fetched, null if not a SendableGet */
	public final ClientKey ckey;

	ChosenRequest(SendableRequest req, Object tok, Key key, ClientKey ckey) {
		request = req;
		token = tok;
		this.key = key;
		this.ckey = ckey;
	}

	public boolean send(NodeClientCore core, RequestScheduler sched) {
		return request.send(core, sched, this);
	}
	
}
