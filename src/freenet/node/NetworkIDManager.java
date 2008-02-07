
package freenet.node;

import java.util.HashMap;
import java.util.HashSet;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;

import freenet.support.Logger;

/**
 * Handles the processing of challenge/response pings as well as the storage of the secrets pertaining thereto.
 * It may (eventually) also handle the separation of peers into network peer groups.
 * @author robert
 * @created 2008-02-06
 */
public class NetworkIDManager {
	public static boolean disableSecretPings=true;
	
	private static final int SECRETPONG_TIMEOUT=10000;
	private final boolean logMINOR;
	
	//Atomic: Locking for both via secretsByPeer
	private final HashMap secretsByPeer=new HashMap();
	private final HashMap secretsByUID=new HashMap();
	
	private final Node node;
	
	NetworkIDManager(Node node) {
		this.node=node;
		this.logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	/**
	 * Stores the secret&uid contained in the message associated with the peer it comes from.
	 * "FNPStoreSecret" messages are *never* forwarded, they are only between peers as an alert
	 * that they may be asked for the secret from a third party.
	 */
	public boolean handleStoreSecret(Message m) {
		PeerNode pn=(PeerNode)m.getSource();
		long uid = m.getLong(DMT.UID);
		long secret = m.getLong(DMT.SECRET);
		StoredSecret s=new StoredSecret(pn, uid, secret);
		Logger.error(this, "Storing secret: "+s);
		addOrReplaceSecret(s);
		try {
			pn.sendAsync(DMT.createFNPAccepted(uid), null, 0, null);
		} catch (NotConnectedException e) {
			Logger.error(this, "peer disconnected before storeSecret ack?", e);
		}
		return true;
	}
	
	public boolean handleSecretPing(final Message m) {
		final PeerNode source=(PeerNode)m.getSource();
		final long uid = m.getLong(DMT.UID);
		final short htl = m.getShort(DMT.HTL);
		final short dawnHtl=m.getShort(DMT.DAWN_HTL);
		final int counter=m.getInt(DMT.COUNTER);
		node.executor.execute(new Runnable() {
		public void run() {
		try {
			_handleSecretPing(m, source, uid, htl, dawnHtl, counter);
		} catch (NotConnectedException e) {
			Logger.normal(this, "secretPing/not connected: "+e);
		}
		}}, "SecretPingHandler for UID "+uid+" on "+node.getDarknetPortNumber());
		return true;
	}
	
	/*
	 @throws NotConnectedException if the *source* goes away
	 */
	private boolean _handleSecretPing(Message m, PeerNode source, long uid, short htl, short dawnHtl, int counter) throws NotConnectedException {
		
		if (disableSecretPings || node.recentlyCompleted(uid)) {
			Logger.normal(this, "recently complete/loop: "+uid);
			source.sendAsync(DMT.createFNPRejectedLoop(uid), null, 0, null);
		} else {
			StoredSecret match;
			//Yes, I know... it looks really weird sync.ing on a separate map...
			synchronized (secretsByPeer) {
				match=(StoredSecret)secretsByUID.get(new Long(uid));
			}
			if (match!=null) {
				//This is the node that the ping intends to reach, we will *not* forward it; but we might not respond positively either.
				//don't set the completed flag, we might reject it from one peer (too short a path) and accept it from another.
				if (htl > dawnHtl) {
					source.sendAsync(DMT.createFNPRejectedLoop(uid), null, 0, null);
				} else {
					Logger.error(this, "Responding to "+source+" with "+match+" from "+match.peer);
					source.sendAsync(match.getSecretPong(counter+1), null, 0, null);
				}
			} else {
				//Set the completed flag immediately for determining reject loops rather than locking the uid.
				node.completed(uid);
				
				//Not a local match... forward
				double target=m.getDouble(DMT.TARGET_LOCATION);
				HashSet routedTo=new HashSet();
				HashSet notIgnored=new HashSet();
				while (true) {
					PeerNode next;
					
					if (htl > dawnHtl && routedTo.isEmpty()) {
						next=node.peers.getRandomPeer(source);
					} else {
						next=node.peers.closerPeer(source, routedTo, notIgnored, target, true, node.isAdvancedModeEnabled(), -1, null, null);
					}
					
					if (next==null) {
						//would be rnf... but this is a more exhaustive and lightweight search I suppose.
						source.sendAsync(DMT.createFNPRejectedLoop(uid), null, 0, null);
						break;
					}
					
					htl=next.decrementHTL(htl);
					
					if (htl<=0) {
						//would be dnf if we were looking for data.
						source.sendAsync(DMT.createFNPRejectedLoop(uid), null, 0, null);
						break;
					}
					
					if (!source.isConnected()) {
						throw new NotConnectedException("source gone away while forwarding");
					}
					
					counter++;
					routedTo.add(next);
					try {
						next.sendAsync(DMT.createFNPSecretPing(uid, target, htl, dawnHtl, counter), null, 0, null);
					} catch (NotConnectedException e) {
						Logger.normal(this, next+" disconnected before secret-ping-forward");
						continue;
					}
					
					//wait for a reject or pong
					MessageFilter mfPong = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPSecretPong);
					MessageFilter mfRejectLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPRejectedLoop);
					Message msg;
					
					try {
						msg = node.usm.waitFor(mfPong.or(mfRejectLoop), null);
					} catch (DisconnectedException e) {
						Logger.normal(this, next+" disconnected while waiting for a secret-pong");
						continue;
					}
					
					if (msg==null) {
						Logger.error(this, "fatal timeout in waiting for secretpong from "+next);
						//backoff?
						break;
					}
					
					if (msg.getSpec() == DMT.FNPSecretPong) {
						int suppliedCounter=msg.getInt(DMT.COUNTER);
						if (suppliedCounter>counter)
							counter=suppliedCounter;
						long secret=msg.getLong(DMT.SECRET);
						Logger.error(this, node+" forwarding apparently-successful secretpong response: "+counter+"/"+secret+" from "+next+" to "+source);
						source.sendAsync(DMT.createFNPSecretPong(uid, counter, secret), null, 0, null);
						break;
					}
					
					if (msg.getSpec() == DMT.FNPRejectedLoop) {
						if (logMINOR) Logger.minor(this, "secret ping (reject/loop): "+source+" -> "+next);
						continue;
					}
					
					Logger.error(this, "unexpected message type: "+msg);
					break;
				}
			}
			//unlockUID()
		}
		return true;
	}
	
	//FIXME: This needs to be wired in.
	public void onDisconnect(PeerNode pn) {
		synchronized (secretsByPeer) {
			StoredSecret s=(StoredSecret)secretsByPeer.get(pn);
			if (s!=null) {
				//???: Might it still be valid to respond to secret pings when the neighbor requesting it has disconnected? (super-secret ping?)
				Logger.error(this, "Removing on disconnect: "+s);
				removeSecret(s);
			}
		}
	}
	
	private void addOrReplaceSecret(StoredSecret s) {
		synchronized (secretsByPeer) {
			StoredSecret prev=(StoredSecret)secretsByPeer.get(s.peer);
			if (prev!=null) {
				Logger.normal(this, "Removing on replacement: "+s);
				removeSecret(prev);
			}
			//Need to remember by peer (so we can remove it on disconnect)
			//Need to remember by uid (so we can respond quickly to arbitrary requests).
			secretsByPeer.put(s.peer, s);
			secretsByUID.put(new Long(s.uid), s);
		}
	}
	
	private void removeSecret(StoredSecret s) {
		//synchronized (secretsByPeer) in calling functions
		secretsByPeer.remove(s);
		secretsByUID.remove(s);
	}
	
	private static final class StoredSecret {
		PeerNode peer;
		long uid;
		long secret;
		StoredSecret(PeerNode peer, long uid, long secret) {
			this.peer=peer;
			this.uid=uid;
			this.secret=secret;
		}
		public String toString() {
			return "Secret("+uid+"/"+secret+")";
		}
		Message getSecretPong(int counter) {
			return DMT.createFNPSecretPong(uid, counter, secret);
		}
	}
}