/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.USKCallback;
import freenet.keys.USK;
import freenet.node.NodeClientCore;

public class SubscribeUSK implements USKCallback {

	// FIXME allow client to specify priorities
	final FCPConnectionHandler handler;
	final String identifier;
	final NodeClientCore core;
	final boolean dontPoll;
	final short prio;
	final short prioProgress;
	final USK usk;
	
	public SubscribeUSK(SubscribeUSKMessage message, NodeClientCore core, FCPConnectionHandler handler) throws IdentifierCollisionException {
		this.handler = handler;
		this.dontPoll = message.dontPoll;
		this.identifier = message.identifier;
		this.core = core;
		this.usk = message.key;
		prio = message.prio;
		prioProgress = message.prioProgress;
		handler.addUSKSubscription(identifier, this);
		core.uskManager.subscribe(message.key, this, !message.dontPoll, handler.getRebootClient().lowLevelClient(message.realTimeFlag));
	}

	public void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean wasMetadata, short codec, byte[] data, boolean newKnownGood, boolean newSlotToo) {
		if(handler.isClosed()) {
			core.uskManager.unsubscribe(key, this);
			return;
		}
		//if(newKnownGood && !newSlotToo) return;
		FCPMessage msg = new SubscribedUSKUpdate(identifier, l, key, newKnownGood, newSlotToo);
		handler.outputHandler.queue(msg);
	}

	public short getPollingPriorityNormal() {
		return prio;
	}

	public short getPollingPriorityProgress() {
		return prioProgress;
	}

	public void unsubscribe() {
		core.uskManager.unsubscribe(usk, this);
	}

}
