/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.node;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.SimpleFieldSet;

/**
 *
 * @author nextgens
 */
public class SeedServerTestPeerNode extends SeedServerPeerNode {

	public SeedServerTestPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, PeerManager peers, boolean fromLocal, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		super(fs, node2, crypto, peers, fromLocal, mangler);
	}
	
	public SimpleFieldSet exportFieldSet() {
		SimpleFieldSet sfs = super.exportFieldSet();
		sfs.putOverwrite("opennet", "true");
		return sfs;
	}

	public boolean shouldDisconnectAndRemoveNow() {
		return false;
	}
	
	protected void sendInitialMessages() {}
	
	public enum FATE {
		// Never connected
		NEVER_CONNECTED,
		// Connected but no packets received yet
		CONNECTED_NO_PACKETS_RECEIVED,
		// Connected and received packets
		CONNECTED_SUCCESS,
		// Connected but timed out after no packets received
		CONNECTED_TIMEOUT_NO_PACKETS_RECEIVED,
		// Connected but then disconnected for no known reason
		CONNECTED_DISCONNECTED_UNKNOWN
	}
	
	public void onRemove() {
		long lastReceivedDataPacketTime = lastReceivedDataPacketTime();
		if(lastReceivedDataPacketTime <= 0 && timeLastConnectionCompleted() > 0)
			System.err.println(this.getIdentityString()+" : REMOVED: TIMEOUT: NO PACKETS RECEIVED AFTER SUCCESSFUL CONNECTION SETUP");
		else if(timeLastConnectionCompleted() <= 0)
			System.err.println(this.getIdentityString()+" : REMOVED: NEVER CONNECTED");
		else
			System.err.println(this.getIdentityString()+" : REMOVED: UNKNOWN CAUSE");
		super.onRemove();
	}
	
	public FATE getFate() {
		long lastReceivedDataPacketTime = lastReceivedDataPacketTime();
		if(isConnected()) {
			if(lastReceivedDataPacketTime <= 0)
				return FATE.CONNECTED_NO_PACKETS_RECEIVED;
			else
				return FATE.CONNECTED_SUCCESS;
		}
		long lastConnectionTime = timeLastConnectionCompleted();
		if(lastConnectionTime <= 0)
			return FATE.NEVER_CONNECTED;
		if(lastReceivedDataPacketTime <= 0)
			return FATE.CONNECTED_TIMEOUT_NO_PACKETS_RECEIVED;
		return FATE.CONNECTED_DISCONNECTED_UNKNOWN;
	}
}
