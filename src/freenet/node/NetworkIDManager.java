
package freenet.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;

import freenet.support.Logger;
import freenet.support.math.BootstrappingDecayingRunningAverage;
import freenet.support.math.RunningAverage;

/**
 * Handles the processing of challenge/response pings as well as the storage of the secrets pertaining thereto.
 * It may (eventually) also handle the separation of peers into network peer groups.
 * @author robert
 * @created 2008-02-06
 */
public class NetworkIDManager implements Runnable {
	public static boolean disableSecretPings=true;
	public static boolean disableSecretPinger=true;
	
	private static final int ACCEPTED_TIMEOUT   =  5000;
	private static final int SECRETPONG_TIMEOUT = 20000;
	
	//Intervals between connectivity checks and NetworkID reckoning.
	//Checks for added peers may be delayed up to LONG_PERIOD, so don't make it too long.
	private static final long BETWEEN_PEERS =   2000;
	private static final long STARTUP_DELAY =  20000;
	private static final long LONG_PERIOD   = 120000;
	
	private final short MAX_HTL;
	private final short MIN_HTL=3;
	private final boolean logMINOR;
	
	private static final int NO_NETWORKID = 0;
	
	//The number of pings, etc. beyond which is considered a sane value to start experimenting from.
	private static final int COMFORT_LEVEL=20;
	
	//Atomic: Locking for both via secretsByPeer
	private final HashMap secretsByPeer=new HashMap();
	private final HashMap secretsByUID=new HashMap();
	
	private final Node node;
	private int startupChecks;
	
	NetworkIDManager(final Node node) {
		this.node=node;
		this.MAX_HTL=node.maxHTL();
		this.logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if (!disableSecretPinger) {
			node.getTicker().queueTimedJob(new Runnable() {
				public void run() {
					checkAllPeers();
					startupChecks = node.peers.quickCountConnectedPeers();
					Logger.normal(NetworkIDManager.this, "Past startup delay, "+startupChecks+" connected peers");
					reschedule(0);
				}
			}, STARTUP_DELAY);
		}
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
		if (logMINOR) Logger.minor(this, "Storing secret: "+s);
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
			if (logMINOR) Logger.minor(this, "recently complete/loop: "+uid);
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
					if (logMINOR) Logger.minor(this, "Responding to "+source+" with "+match+" from "+match.peer);
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
						if (logMINOR) Logger.minor(this, node+" forwarding apparently-successful secretpong response: "+counter+"/"+secret+" from "+next+" to "+source);
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
				Logger.normal(this, "Removing on disconnect: "+s);
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
	
	private final class PingRecord {
		PeerNode target;
		PeerNode via;
		long lastSuccess=-1;
		long lastTry=-1;
		int shortestSuccess=Integer.MAX_VALUE;
		RunningAverage average=new BootstrappingDecayingRunningAverage(0.0, 0.0, 1.0, 200, null);
		RunningAverage sHtl=new BootstrappingDecayingRunningAverage(MAX_HTL, 0.0, MAX_HTL, 200, null);
		RunningAverage fHtl=new BootstrappingDecayingRunningAverage(MAX_HTL, 0.0, MAX_HTL, 200, null);
		RunningAverage sDawn=new BootstrappingDecayingRunningAverage(0.0, 0.0, MAX_HTL, 200, null);
		RunningAverage fDawn=new BootstrappingDecayingRunningAverage(0.0, 0.0, MAX_HTL, 200, null);
		public String toString() {
			return "percent="+average.currentValue();
		}
		public void success(int counter, short htl, short dawn) {
			long now=System.currentTimeMillis();
			lastTry=now;
			lastSuccess=now;
			average.report(1.0);
			if (counter < shortestSuccess)
				shortestSuccess=counter;
			dawn=(short)(htl-dawn);
			sHtl.report(htl);
			sDawn.report(dawn);
		}
		public void failure(int counter, short htl, short dawn) {
			long now=System.currentTimeMillis();
			lastTry=now;
			average.report(0.0);
			dawn=(short)(htl-dawn);
			fHtl.report(htl);
			fDawn.report(dawn);
		}
		/**
		 * Written to start high and slowly restrict the htl at 80%.
		 */
		public short getNextHtl() {
			if (sHtl.countReports()<COMFORT_LEVEL) {
				return MAX_HTL;
			} else if (average.currentValue()>0.8) {
				//looking good, try lower htl
				short htl=(short)(sHtl.currentValue()-0.5);
				if (htl<MIN_HTL)
					htl=MIN_HTL;
				return htl;
			} else {
				//not so good, try higher htl
				short htl=(short)(sHtl.currentValue()+0.5);
				if (htl>MAX_HTL)
					htl=MAX_HTL;
				return htl;
			}
		}
		/**
		 * Written to start with 2 random hops, and slowly expand if too many failures.
		 * Will not use more than 1/2 the hops. For good connections, should always be 2.
		 */
		public short getNextDawnHtl(short htl) {
			//the number of random hops (htl-dawn)
			short diff;
			short max=(short)(htl/2-1);
			if (fDawn.countReports()<COMFORT_LEVEL) {
				diff=2;
			} else if (sDawn.countReports()<COMFORT_LEVEL) {
				//We've had enough failures, not successes
				diff=(short)(fDawn.currentValue()+0.5);
			} else {
				//Just a different algorithim than getNextHtl() so that they both might stabilize...
				diff=(short)(0.25*fDawn.currentValue()+0.75*sDawn.currentValue());
			}
			if (diff>max)
				diff=max;
			return (short)(htl-diff);
		}
		public boolean equals(Object o) {
			PeerNode p=(PeerNode)o;
			return (via.equals(p));
		}
		public int hashCode() {
			return via.hashCode();
		}
	}
	
	//Directional lists of reachability, a "Map of Maps" of peers to pingRecords.
	//This is asymetric; so recordsByPeer.get(a).get(b) [i.e. a's reachability through peer b] may not
	//be nearly the same as recordsByPeer.get(b).get(a) [i.e. b's reachability through peer a].
	private HashMap recordMapsByPeer=new HashMap();
	
	private PingRecord getPingRecord(PeerNode target, PeerNode via) {
		PingRecord retval;
		synchronized (recordMapsByPeer) {
			HashMap peerRecords=(HashMap)recordMapsByPeer.get(target);
			if (peerRecords==null) {
				//no record of any pings towards target
				peerRecords=new HashMap();
				recordMapsByPeer.put(target, peerRecords);
			}
			retval=(PingRecord)peerRecords.get(via);
			if (retval==null) {
				//no records via this specific peer
				retval=new PingRecord();
				retval.target=target;
				retval.via=via;
				peerRecords.put(via, retval);
			}
		}
		return retval;
	}
	
	private void forgetPingRecords(PeerNode p) {
		synchronized (workQueue) {
			workQueue.remove(p);
			if (p.equals(processing)) {
				//don't chase the thread making records, signal a fault.
				processingRace=true;
				return;
			}
		}
		synchronized (recordMapsByPeer) {
			recordMapsByPeer.remove(p);
			Iterator i=recordMapsByPeer.values().iterator();
			while (i.hasNext()) {
				HashMap complement=(HashMap)i.next();
				//FIXME: NB: Comparing PeerNodes with PingRecords.
				complement.values().remove(p);
			}
		}
	}
	
	private List workQueue=new ArrayList();
	private PeerNode processing;
	private boolean processingRace;
	
	private void reschedule(long period) {
		node.getTicker().queueTimedJob(this, period);
	}
	
	public void run() {
		//pick a target
		synchronized (workQueue) {
			if (processing!=null) {
				Logger.error(this, "possibly *bad* programming error, only one thread should use secretpings");
				return;
			}
			if (!workQueue.isEmpty())
				processing=(PeerNode)workQueue.remove(0);
		}
		if (processing!=null) {
			PeerNode target=processing;
			double randomTarget=node.random.nextDouble();
			HashSet nodesRoutedTo = new HashSet();
			PeerNode next = node.peers.closerPeer(target, nodesRoutedTo, null, randomTarget, true, false, -1, null, null);
			while (next!=null && target.isRoutable() && !processingRace) {
				nodesRoutedTo.add(next);
				//the order is not that important, but for all connected peers try to ping 'target'
				blockingUpdatePingRecord(target, next);
				//Since we are causing traffic to 'target'
				betweenPingSleep(target);
				next = node.peers.closerPeer(target, nodesRoutedTo, null, randomTarget, true, false, -1, null, null);
			}
		}
		boolean didAnything;
		synchronized (workQueue) {
			didAnything=(processing!=null);
			//how sad... all that work may be garbage.
			if (processingRace) {
				processingRace=false;
				//processing must not be null now, but must be null when we call the forget() function.
				PeerNode target=processing;
				processing=null;
				forgetPingRecords(target);
			}
			processing=null;
		}
		if (startupChecks>0)
			startupChecks--;
		else
			doNetworkIDReckoning(didAnything);
		synchronized (workQueue) {
			if (workQueue.isEmpty()) {
				//Add two random peers to check next time, or maybe... all of them?
				PeerNode p1=node.peers.getRandomPeer();
				PeerNode p2=node.peers.getRandomPeer(p1);
				addWorkToLockedQueue(p1);
				addWorkToLockedQueue(p2);
				reschedule(LONG_PERIOD);
			} else {
				reschedule(BETWEEN_PEERS);
			}
		}
	}
	
	public long secretPingSuccesses;
	public long totalSecretPingAttempts;
	
	// Best effort ping from next to target, if anything out of the ordinary happens, it counts as a failure.
	private void blockingUpdatePingRecord(PeerNode target, PeerNode next) {
		//make a secret & uid
		long uid=node.random.nextLong();
		long secret=node.random.nextLong();
		PingRecord record=getPingRecord(target, next);
		short htl=record.getNextHtl();
		short dawn=record.getNextDawnHtl(htl);
		
		boolean success=false;
		int suppliedCounter=1;
		
		totalSecretPingAttempts++;
		
		try {
			//store secret in target
			target.sendSync(DMT.createFNPStoreSecret(uid, secret), null);
			
			//Wait for an accepted or give up
			MessageFilter mfAccepted = MessageFilter.create().setSource(target).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
			Message msg = node.usm.waitFor(mfAccepted, null);
			
			if (msg==null || (msg.getSpec() != DMT.FNPAccepted)) {
				//backoff?
				Logger.error(this, "peer is unresponsive to StoreSecret "+target);
				return;
			}
			
			//next... send a secretping through next to target
			next.sendSync(DMT.createFNPSecretPing(uid, target.getLocation(), htl, dawn, 0), null);
			
			//wait for a response; SecretPong, RejectLoop, or timeout
			MessageFilter mfPong = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPSecretPong);
			MessageFilter mfRejectLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPRejectedLoop);
			
			msg = node.usm.waitFor(mfPong.or(mfRejectLoop), null);
			
			if (msg==null) {
				Logger.error(this, "fatal timeout in waiting for secretpong from "+next);
			} else if (msg.getSpec() == DMT.FNPSecretPong) {
				suppliedCounter=msg.getInt(DMT.COUNTER);
				long suppliedSecret=msg.getLong(DMT.SECRET);
				if (logMINOR) Logger.minor(this, "got secret, counter="+suppliedCounter);
				success=(secret==suppliedSecret);
			} else if (msg.getSpec() == DMT.FNPRejectedLoop) {
				Logger.normal(this, "top level rejectLoop (no route found): "+next+" -> "+target);
			}
		} catch (NotConnectedException e) {
			Logger.normal(this, "one party left during connectivity test: "+e);
		} catch (DisconnectedException e) {
			Logger.normal(this, "one party left during connectivity test: "+e);
		} finally {
			if (success) {
				secretPingSuccesses++;
				record.success(suppliedCounter, htl, dawn);
			} else {
				record.failure(suppliedCounter, htl, dawn);
			}
		}
	}
	
	private void betweenPingSleep(PeerNode target) {
		//We are currently sending secret pings to target, sleep for a while to be nice; could increase for cause of target's backoff.
		try {
			Thread.sleep(200);
		} catch (InterruptedException e) {
			//ignore
		}
	}
	
	private void addWorkToLockedQueue(PeerNode p) {
		if (p!=null && !workQueue.contains(p))
			workQueue.add(p);
	}
	
	public void checkAllPeers() {
		double randomTarget=node.random.nextDouble();
		HashSet connectedPeers = new HashSet();
		PeerNode next = node.peers.closerPeer(null, connectedPeers, null, randomTarget, true, false, -1, null, null);
		while (next!=null) {
			connectedPeers.add(next);
			next = node.peers.closerPeer(null, connectedPeers, null, randomTarget, true, false, -1, null, null);
		}
		Iterator i=connectedPeers.iterator();
		synchronized (workQueue) {
			while (i.hasNext()) {
				addWorkToLockedQueue((PeerNode)i.next());
			}
		}
	}
	
	/**
	 * Takes all the stored PingRecords, combines it with the network id's advertised by our peers,
	 * and then does the monstrous task of doing something useful with that data. At the end of this
	 * function we must assign and broadcast a network id to each of our peers; or at least the ones
	 * we have ping records for this time around; even if it is just totally madeup identifiers.
	 */
	private void doNetworkIDReckoning(boolean anyPingChanges) {
		// [...!!!...] \\
	}
	
	public void onPeerNodeChangedNetworkID(PeerNode p) {
		/*
		 If the network group we assigned to them is (unstable?)... that is; we would have made a
		 different assignment based on there preference, change the network id for that entire group
		 and readvertise it to the peers.
		 
		 This helps the network form a consensus much more quickly by not waiting for the next round
		 of peer-secretpinging/and network-id-reckoning. Note that we must still not clobber priorities
		 so...

		 //do nothing on: p.getNetGroup().disallowedIds.contains(p.getNetID());
		 //do nothing on: allAssignedNetGroups.contains(p.getNetID());

		 There is a minor race condition here that between updates we might improperly favor the first
		 peer to notify us of a new network id, but this will be authoritatively clobbered next round.
		 */
	}
	
	/**
	 A list of peers that we have assigned a network id to, and some logic as to why.
	 */
	private static class PeerNetworkGroup {
		List members;
		int networkid;
		boolean ourGroup;
		int getConsensus() {
			//mod(peers[].getNetworkID() + ourGroup?ourID:NO_NETWORKID )
			return NO_NETWORKID;
		}
	}
	
	//or zero if we don't know yet
	public int ourNetworkId = NO_NETWORKID;
}