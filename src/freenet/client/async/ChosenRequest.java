/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.NodeClientCore;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
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
	/** Priority when we selected it */
	public short prio;
	public final boolean localRequestOnly;
	public final boolean cacheLocalRequests;
	public final boolean ignoreStore;

	ChosenRequest(SendableRequest req, Object tok, Key key, ClientKey ckey, short prio) {
		request = req;
		token = tok;
		if(key != null)
			this.key = key.cloneKey();
		else
			this.key = null;
		if(ckey != null)
			this.ckey = ckey.cloneKey();
		else
			this.ckey = null;
		this.prio = prio;
		if(req instanceof SendableGet) {
			SendableGet sg = (SendableGet) req;
			FetchContext ctx = sg.getContext();
			localRequestOnly = ctx.localRequestOnly;
			cacheLocalRequests = ctx.cacheLocalRequests;
			ignoreStore = ctx.ignoreStore;
		} else {
			localRequestOnly = false;
			cacheLocalRequests = false;
			ignoreStore = false;
		}
	}

	public boolean send(NodeClientCore core, RequestScheduler sched) {
		return request.send(core, sched, this);
	}

	public boolean isPersistent() {
		return this instanceof PersistentChosenRequest;
	}
	
	public boolean equals(Object o) {
		if(!(o instanceof ChosenRequest)) return false;
		ChosenRequest cr = (ChosenRequest) o;
		if(!cr.request.equals(request)) return false;
		if(!cr.token.equals(token)) return false;
		if(cr.key != null) {
			if(key != null) {
				if(!key.equals(cr.key)) return false;
			} else return false;
		} else {
			if(key != null) return false;
		}
		if(cr.ckey != null) {
			if(ckey != null) {
				if(!ckey.equals(cr.ckey)) return false;
			} else return false;
		} else {
			if(ckey != null) return false;
		}
		return true;
	}
}
