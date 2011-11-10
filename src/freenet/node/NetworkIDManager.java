
package freenet.node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.Logger.LogLevel;
import freenet.support.math.BootstrappingDecayingRunningAverage;
import freenet.support.math.RunningAverage;
import freenet.support.math.TrivialRunningAverage;

/**
 * Handles the processing of challenge/response pings as well as the storage of the secrets pertaining thereto.
 * It may (eventually) also handle the separation of peers into network peer groups.
 * @author robert
 * @created 2008-02-06
 */
public class NetworkIDManager implements Runnable, Comparator<NetworkIDManager.PeerNetworkGroup> {
	public static boolean disableSecretPings = true;
	public static boolean disableSecretPinger = true;
	
	private static final int ACCEPTED_TIMEOUT   =  5000;
	private static final int SECRETPONG_TIMEOUT = 20000;
	
	//Intervals between connectivity checks and NetworkID reckoning.
	//Checks for added peers may be delayed up to LONG_PERIOD, so don't make it too long.
	//Coincidentally, LONG_PERIOD is also the interval at which we send out FNPNetworkID reminders.
	private static final long BETWEEN_PEERS =   2000;
	private static final long STARTUP_DELAY =  20000;
	private static final long LONG_PERIOD   = 120000;
	
	private final short MAX_HTL;
	private static final short MIN_HTL = 3;
	private final boolean logMINOR;
	
	private static final int NO_NETWORKID = 0;
	
	//The minimum number of pings per-node we will try and send out before doing any kind of network id reasoning.
	private static final int MIN_PINGS_FOR_STARTUP=3;
	//The number of pings, etc. beyond which is considered a sane value to start experimenting from.
	private static final int COMFORT_LEVEL=20;
	//e.g. ping this many of your N peers, then see if the network has changed; this times BETWEEN_PEERS in the min. time between network id changes.
	private static final int PING_VOLLEYS_PER_NETWORK_RECOMPUTE = 5;
	
	//Atomic: Locking for both via secretsByPeer
	private final HashMap<PeerNode, StoredSecret> secretsByPeer = new HashMap<PeerNode, StoredSecret>();
	private final HashMap<Long, StoredSecret> secretsByUID = new HashMap<Long, StoredSecret>();
	
	//1.0 is disabled, this amounts to a threshold; if connectivity between peers in > this, they get there own group for sure.
	private static final double MAGIC_LINEAR_GRACE = 0.8;
	//Commit everyone with less than this amount of "connectivity" to there own networkgroup.
	//Provides fall-open effect by grouping all peers with disabled secretpings into there own last group.
	private static final double FALL_OPEN_MARK = 0.2;
	
	private final Node node;
	private int startupChecks;
	
	NetworkIDManager(final Node node) {
		this.node=node;
		this.MAX_HTL=node.maxHTL();
		this.logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if (!disableSecretPinger) {
			node.getTicker().queueTimedJob(new Runnable() {
				@Override
				public void run() {
					checkAllPeers();
					startupChecks = node.peers.quickCountConnectedPeers() * MIN_PINGS_FOR_STARTUP;
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
		if(disableSecretPings) return true;
		PeerNode pn=(PeerNode)m.getSource();
		long uid = m.getLong(DMT.UID);
		long secret = m.getLong(DMT.SECRET);
		StoredSecret s=new StoredSecret(pn, uid, secret);
		if (logMINOR) Logger.minor(this, "Storing secret: "+s);
		addOrReplaceSecret(s); // FIXME - what if the message contain a bogus UID?
		try {
			pn.sendAsync(DMT.createFNPAccepted(uid), null, ctr);
		} catch (NotConnectedException e) {
			Logger.error(this, "peer disconnected before storeSecret ack?", e);
		}
		return true;
	}
	
	public boolean handleSecretPing(final Message m) {
		if(disableSecretPings) return true;
		final PeerNode source=(PeerNode)m.getSource();
		final long uid = m.getLong(DMT.UID);
		final short htl = m.getShort(DMT.HTL);
		final short dawnHtl=m.getShort(DMT.DAWN_HTL);
		final int counter=m.getInt(DMT.COUNTER);
		node.executor.execute(new Runnable() {
		@Override
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
			source.sendAsync(DMT.createFNPRejectedLoop(uid), null, ctr);
		} else {
			byte[] nodeIdentity = ((ShortBuffer) m.getObject(DMT.NODE_IDENTITY)).getData();
			StoredSecret match;
			//Yes, I know... it looks really weird sync'ing on a separate map...
			synchronized (secretsByPeer) {
				match = secretsByUID.get(uid);
			}
			if (match!=null) {
				//This is the node that the ping intends to reach, we will *not* forward it; but we might not respond positively either.
				//don't set the completed flag, we might reject it from one peer (too short a path) and accept it from another.
				if (htl > dawnHtl) {
					source.sendAsync(DMT.createFNPRejectedLoop(uid), null, ctr);
				} else {
					if (logMINOR) Logger.minor(this, "Responding to "+source+" with "+match+" from "+match.peer);
					source.sendAsync(match.getSecretPong(counter+1), null, ctr);
				}
			} else {
				//Set the completed flag immediately for determining reject loops rather than locking the uid.
				node.completed(uid);
				
				//Not a local match... forward
				double target=m.getDouble(DMT.TARGET_LOCATION);
				HashSet<PeerNode> routedTo = new HashSet<PeerNode>();
				while (true) {
					PeerNode next;
					
					if (htl > dawnHtl && routedTo.isEmpty()) {
						next=node.peers.getRandomPeer(source);
					} else {
						next = node.peers.closerPeer(source, routedTo, target, true, node.isAdvancedModeEnabled(), -1,
						        null, null, htl, 0, source == null, false, false);
					}
					
					if (next==null) {
						//would be rnf... but this is a more exhaustive and lightweight search I suppose.
						source.sendAsync(DMT.createFNPRejectedLoop(uid), null, ctr);
						break;
					}
					
					htl=next.decrementHTL(htl);
					
					if (htl<=0) {
						//would be dnf if we were looking for data.
						source.sendAsync(DMT.createFNPRejectedLoop(uid), null, ctr);
						break;
					}
					
					if (!source.isConnected()) {
						throw new NotConnectedException("source gone away while forwarding");
					}
					
					counter++;
					routedTo.add(next);
					try {
						next.sendAsync(DMT.createFNPSecretPing(uid, target, htl, dawnHtl, counter, nodeIdentity), null, ctr);
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
						source.sendAsync(DMT.createFNPSecretPong(uid, counter, secret), null, ctr);
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
			StoredSecret s = secretsByPeer.get(pn);
			if (s!=null) {
				//???: Might it still be valid to respond to secret pings when the neighbor requesting it has disconnected? (super-secret ping?)
				Logger.normal(this, "Removing on disconnect: "+s);
				removeSecret(s);
			}
		}
	}
	
	private void addOrReplaceSecret(StoredSecret s) {
		synchronized (secretsByPeer) {
			StoredSecret prev = secretsByPeer.get(s.peer);
			if (prev!=null) {
				if (logMINOR) Logger.minor(this, "Removing on replacement: "+s);
				removeSecret(prev);
			}
			//Need to remember by peer (so we can remove it on disconnect)
			//Need to remember by uid (so we can respond quickly to arbitrary requests).
			secretsByPeer.put(s.peer, s);
			secretsByUID.put(s.uid, s);
		}
	}
	
	private void removeSecret(StoredSecret s) {
		//synchronized (secretsByPeer) in calling functions
		secretsByPeer.remove(s.peer);
		secretsByUID.remove(s.uid);
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
		@Override
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
		@Override
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
		@Override
        public boolean equals(Object obj) {
	        if (this == obj)
		        return true;
	        if (obj == null)
		        return false;
	        if (getClass() != obj.getClass())
		        return false;
	         else if (!via.equals(((PingRecord) obj).via))
		        return false;
	        return true;
        }
		@Override
		public int hashCode() {
			return via.hashCode();
		}
	}
	
	//Directional lists of reachability, a "Map of Maps" of peers to pingRecords.
	//This is asymmetric; so recordsByPeer.get(a).get(b) [i.e. a's reachability through peer b] may not
	//be nearly the same as recordsByPeer.get(b).get(a) [i.e. b's reachability through peer a].
	private HashMap<PeerNode, HashMap<PeerNode, PingRecord>> recordMapsByPeer = new HashMap<PeerNode, HashMap<PeerNode, PingRecord>>();
	
	private PingRecord getPingRecord(PeerNode target, PeerNode via) {
		PingRecord retval;
		synchronized (recordMapsByPeer) {
			HashMap<PeerNode, PingRecord> peerRecords = recordMapsByPeer.get(target);
			if (peerRecords==null) {
				//no record of any pings towards target
				peerRecords = new HashMap<PeerNode, PingRecord>();
				recordMapsByPeer.put(target, peerRecords);
			}
			retval = peerRecords.get(via);
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
			for (HashMap<PeerNode, PingRecord> complement : recordMapsByPeer.values()) {
				//FIXME: NB: Comparing PeerNodes with PingRecords.
				complement.values().remove(p);
			}
		}
	}
	
	private List<PeerNode> workQueue = new ArrayList<PeerNode>();
	private PeerNode processing;
	private boolean processingRace;
	private int pingVolleysToGo=PING_VOLLEYS_PER_NETWORK_RECOMPUTE;
	
	private void reschedule(long period) {
		node.getTicker().queueTimedJob(this, period);
	}
	
	@Override
	public void run() {
		//pick a target
		synchronized (workQueue) {
			if (processing!=null) {
				Logger.error(this, "possibly *bad* programming error, only one thread should use secretpings");
				return;
			}
			if (!workQueue.isEmpty())
				processing = workQueue.remove(0);
		}
		if (processing!=null) {
			PeerNode target=processing;
			double randomTarget=node.random.nextDouble();
			HashSet<PeerNode> nodesRoutedTo = new HashSet<PeerNode>();
			PeerNode next = node.peers.closerPeer(target, nodesRoutedTo, randomTarget, true, false, -1, null, null, node.maxHTL(), 0, target == null, false, false);
			while (next!=null && target.isRoutable() && !processingRace) {
				nodesRoutedTo.add(next);
				//the order is not that important, but for all connected peers try to ping 'target'
				blockingUpdatePingRecord(target, next);
				//Since we are causing traffic to 'target'
				betweenPingSleep(target);
				next = node.peers.closerPeer(target, nodesRoutedTo, randomTarget, true, false, -1, null, null, node.maxHTL(), 0, target == null, false, false);
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
		pingVolleysToGo--;
		if (startupChecks>0) {
			startupChecks--;
		} else {
			if (pingVolleysToGo<=0) {
				doNetworkIDReckoning(didAnything);
				pingVolleysToGo=PING_VOLLEYS_PER_NETWORK_RECOMPUTE;
			}
		}
		synchronized (workQueue) {
			if (workQueue.isEmpty()) {
				checkAllPeers();
				if (startupChecks>0) {
					reschedule(BETWEEN_PEERS);
				} else {
					reschedule(LONG_PERIOD);
				}
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
			target.sendSync(DMT.createFNPStoreSecret(uid, secret), null, false);
			
			//Wait for an accepted or give up
			MessageFilter mfAccepted = MessageFilter.create().setSource(target).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
			Message msg = node.usm.waitFor(mfAccepted, null);
			
			if (msg==null || (msg.getSpec() != DMT.FNPAccepted)) {
				//backoff?
				Logger.error(this, "peer is unresponsive to StoreSecret "+target);
				return;
			}
			
			//next... send a secretping through next to target
			next.sendSync(DMT.createFNPSecretPing(uid, target.getLocation(), htl, dawn, 0, target.identity), null, false);
			
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
		} catch (SyncSendWaitedTooLongException e) {
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
		Set<PeerNode> set = getAllConnectedPeers();
		synchronized (workQueue) {
			for (PeerNode p : set) {
				addWorkToLockedQueue(p);
			}
		}
	}
	
	private HashSet<PeerNode> getAllConnectedPeers() {
		double randomTarget=node.random.nextDouble();
		HashSet<PeerNode> connectedPeers = new HashSet<PeerNode>();
		PeerNode next = node.peers.closerPeer(null, connectedPeers, randomTarget, true, false, -1, null, null, node.maxHTL(), 0, true, false, false);
		while (next!=null) {
			connectedPeers.add(next);
			next = node.peers.closerPeer(null, connectedPeers, randomTarget, true, false, -1, null, null, node.maxHTL(), 0, true, false, false);
		}
		return connectedPeers;
	}
	
	/**
	 * Takes all the stored PingRecords, combines it with the network id's advertised by our peers,
	 * and then does the monstrous task of doing something useful with that data. At the end of this
	 * function we must assign and broadcast a network id to each of our peers; or at least the ones
	 * we have ping records for this time around; even if it is just totally madeup identifiers.
	 */
	private void doNetworkIDReckoning(boolean anyPingChanges) {
		//!!!: This is where the magic separation logic begins.
		// This may still need a lot of work; e.g. a locking mechanism, considering disconnected peers?
		List<PeerNetworkGroup> newNetworkGroups = new ArrayList<PeerNetworkGroup>();
		HashSet<PeerNode> all = getAllConnectedPeers();
		@SuppressWarnings("unchecked")
		HashSet<PeerNode> todo = (HashSet<PeerNode>) all.clone();
		
		synchronized (transitionLock) {
			inTransition=true;
		}
		
		if (logMINOR) Logger.minor(this, "doNetworkIDReckoning for "+all.size()+" peers");
		
		if (todo.isEmpty())
			return;
		
		//optimization, if no stats have changed, just rescan the list consensus?
		
		//Note that in all this set manipulation, we never consult in what group a user previously was.
		while (!todo.isEmpty()) {
			PeerNode mostConnected=findMostConnectedPeerInSet(todo, all);
			PeerNetworkGroup newGroup = new PeerNetworkGroup();
			newNetworkGroups.add(newGroup);
			todo.remove(mostConnected);
			List<PeerNode> members;
			if (todo.isEmpty()) {
				//sad... it looks like this guy gets a group to himself
				members = new ArrayList<PeerNode>();
				members.add(mostConnected);
			} else {
				//NB: as a side effect, this function will automatically remove the members from 'todo'.
				members=xferConnectedPeerSetFor(mostConnected, todo);
			}
			newGroup.setMembers(members);
		}
		
		//The groups are broken up, now sort by priority & assign them a network id.
		Collections.sort(newNetworkGroups, this);
		
		HashSet<Integer> takenNetworkIds = new HashSet<Integer>();
		
		for (PeerNetworkGroup newGroup : newNetworkGroups) {
			newGroup.setForbiddenIds(takenNetworkIds);
			
			int id=newGroup.getConsensus(true);
			if (id==NO_NETWORKID)
				id=node.random.nextInt();
			newGroup.assignNetworkId(id);
			takenNetworkIds.add(id);
			if (logMINOR) Logger.minor(this, "net "+id+" has "+newGroup.members.size()+" peers");
		}
		
		synchronized (transitionLock) {
			PeerNetworkGroup ourgroup = newNetworkGroups.get(0);
			ourNetworkId=ourgroup.networkid;
			
			Logger.error(this, "I am in network: "+ourNetworkId+", and have divided my "+all.size()+" peers into "+newNetworkGroups.size()+" network groups");
			Logger.error(this, "largestGroup="+ourgroup.members.size());
			Logger.error(this, "bestFirst="+cheat_stats_general_bestOther.currentValue());
			Logger.error(this, "bestGeneralFactor="+cheat_stats_findBestSetwisePingAverage_best_general.currentValue());
			
			networkGroups=newNetworkGroups;
			
			inTransition=false;
		}
	}
	
	// Returns the 'best-connected' peer in the given set, or null if the set is empty.
	private PeerNode findMostConnectedPeerInSet(HashSet<PeerNode> set, HashSet<PeerNode> possibleTargets) {
		double max=-1.0;
		PeerNode theMan=null;
		
		for (PeerNode p : set) {
			double value=getPeerNodeConnectedness(p, possibleTargets);
			if (value>max) {
				max=value;
				theMan=p;
			}
		}
		
		return theMan;
	}
	
	// Return a double between [0.0-1.0] somehow indicating how "wellconnected" this peer is to all the peers in possibleTargets.
	private double getPeerNodeConnectedness(PeerNode p, HashSet<PeerNode> possibleTargets) {
		double retval=1.0;
		double totalLossFactor=1.0/possibleTargets.size();
		for (PeerNode target : possibleTargets) {
			PingRecord record=getPingRecord(p, target);
			double pingAverage=record.average.currentValue();
			if (pingAverage<totalLossFactor)
				retval*=totalLossFactor;
			else
				retval*=pingAverage;
		}
		return retval;
	}
	
	/*
	 * Returns the set of peers which appear to be reasonably connected to 'thisPeer' and as a
	 * side effect removes those peers from the set passed in. The set includes at-least the
	 * given peer (will never return an empty list).
	 */
	private List<PeerNode> xferConnectedPeerSetFor(PeerNode thisPeer, HashSet<PeerNode> fromOthers) {
		//FIXME: This algorithm needs to be thought about! Maybe some hard thresholds.
		//       What recently-connected, peers who only have one or two pings so far?
		/*
		 Idea: Right now thisPeer is in a network group by itself, but we know that it is the
		       best connected peer, so now we just need to find it's peers. In this implementation
		       A peer belongs to this newly forming network group if it is at least as connected to
		       the new forming group as the first peer is connected to the original group.
		       Why? I don't know...
		 */
		List<PeerNode> currentGroup = new ArrayList<PeerNode>();
		currentGroup.add(thisPeer);
		//HashSet remainder=others.clone();
		HashSet<PeerNode> remainder = fromOthers;
		double goodConnectivity=getSetwisePingAverage(thisPeer, fromOthers);
		if (goodConnectivity < FALL_OPEN_MARK) {
			Logger.normal(this, "falling open with "+fromOthers.size()+" peers left");
			currentGroup.addAll(fromOthers);
			fromOthers.clear();
			cheat_stats_general_bestOther.report(0.0);
			return currentGroup;
		}
		
		cheat_stats_general_bestOther.report(goodConnectivity);
		goodConnectivity *= MAGIC_LINEAR_GRACE;
		while (!remainder.isEmpty()) {
			//Note that, because of the size, this might be low.
			PeerNode bestOther=findBestSetwisePingAverage(remainder, currentGroup);
			if (cheat_findBestSetwisePingAverage_best >= goodConnectivity) {
				remainder.remove(bestOther);
				currentGroup.add(bestOther);
			} else {
				break;
			}
		}
		//Exception! If there is only one left in fromOthers and we have at least a 25% ping average make them be in the same network. This probably means our algorithim is too picky (spliting up into too many groups).
		if (currentGroup.size()==1 && fromOthers.size()==1) {
			PeerNode onlyLeft = fromOthers.iterator().next();
			double average1=getPingRecord(onlyLeft, thisPeer).average.currentValue();
			double average2=getPingRecord(thisPeer, onlyLeft).average.currentValue();
			if (0.5*average1+0.5*average2 > 0.25) {
				Logger.normal(this, "combine the dregs: "+thisPeer+"/"+fromOthers);
				fromOthers.remove(onlyLeft);
				currentGroup.add(onlyLeft);
			}
		}
		return currentGroup;
	}
	
	private double getSetwisePingAverage(PeerNode thisPeer, Collection<PeerNode> toThesePeers) {
		Iterator<PeerNode> i = toThesePeers.iterator();
		double accum=0.0;
		if (!i.hasNext()) { // FIXME this skip the first element, investigate if is this intentional
			//why yes, we have GREAT connectivity to nobody!
			Logger.error(this, "getSetwisePingAverage to nobody?");
			return 1.0;
		}
		while (i.hasNext()) {
			PeerNode other = i.next();
			accum+=getPingRecord(thisPeer, other).average.currentValue();
		}
		return accum/toThesePeers.size();
	}
	
	private PeerNode findBestSetwisePingAverage(HashSet<PeerNode> ofThese, Collection<PeerNode> towardsThese) {
		PeerNode retval=null;
		double best=-1.0;
		Iterator<PeerNode> i = ofThese.iterator();
		if (!i.hasNext()) { // FIXME this skip the first element, investigate if is this intentional
			//why yes, we have GREAT connectivity to nobody!
			Logger.error(this, "findBestSetwisePingAverage to nobody?");
			return null;
		}
		while (i.hasNext()) {
			PeerNode thisOne = i.next();
			double average=getSetwisePingAverage(thisOne, towardsThese);
			if (average>best) {
				retval=thisOne;
				best=average;
			}
		}
		cheat_findBestSetwisePingAverage_best=best;
		cheat_stats_findBestSetwisePingAverage_best_general.report(best);
		return retval;
	}
	
	private double cheat_findBestSetwisePingAverage_best;
	private RunningAverage cheat_stats_general_bestOther=new TrivialRunningAverage();
	private RunningAverage cheat_stats_findBestSetwisePingAverage_best_general=new TrivialRunningAverage();
	
	boolean inTransition=false;
	Object transitionLock=new Object();
	
	public void onPeerNodeChangedNetworkID(PeerNode p) {
		/*
		 If the network group we assigned to them is (unstable?)... that is; we would have made a
		 different assignment based on there preference, change the network id for that entire group
		 and readvertise it to the peers.
		 
		 This helps the network form a consensus much more quickly by not waiting for the next round
		 of peer-secretpinging/and network-id-reckoning. Note that we must still not clobber priorities
		 so...

		 //do nothing if inTransition;
		 //do nothing on: p.getNetGroup().disallowedIds.contains(p.getNetID());
		 //do nothing on: allAssignedNetGroups.contains(p.getNetID());

		 There is a minor race condition here that between updates we might improperly favor the first
		 peer to notify us of a new network id, but this will be authoritatively clobbered next round.
		 */
		synchronized (transitionLock) {
			if (inTransition)
				return;
			//Networks are listed in order of priority, generally the biggest one should be first.
			//The forbidden ids is already set in this way, but if we decide that one group needs to use the id of a lesser group, we must tell the other group to use a different one; i.e. realign all the previous id's.
			boolean haveFoundIt=false;
			PeerNetworkGroup mine=p.networkGroup;
			HashSet<Integer> nowTakenIds = new HashSet<Integer>();
			for (PeerNetworkGroup png : networkGroups) {
				if (png.equals(mine)) {
					haveFoundIt=true;
					//should be the same: png.setForbiddenIds(nowTakenIds);
					int oldId=png.networkid;
					int newId=png.getConsensus(true);
					/*
					if (png.ourGroup) {
						//Even if the consensus changes, we'll hold onto our group network id label.
						//Important for stability and future routing.
						return;
					} else
					 */
					if (oldId==newId) {
						//Maybe they agree with us, maybe not; but it doesn't change our view of the group.
						return;
					} else {
						if (png.recentlyAssigned()) {
							//In order to keep us from thrashing; e.g. two peers each see each other as in the same
							//group and keep swapping... we are going to ignore this change for now.
							return;
						} else {
							png.assignNetworkId(newId);
						}
					}
					//to continue means to realign all the remaining forbidden ids.
					nowTakenIds.add(newId);
				} else if (haveFoundIt) {
					//lower priority group, it may need to be reset.
					//???: Should we take this opportunity to always re-examine the consensus? This is a callback, so let's not.
					png.setForbiddenIds(nowTakenIds);
					int oldId=png.networkid;
					int newId=oldId;
					if (nowTakenIds.contains(oldId)) {
						newId=png.getConsensus(true);
						png.assignNetworkId(newId);
					}
					nowTakenIds.add(newId);
				} else {
					//higher priority group, remember it's id.
					nowTakenIds.add(png.networkid);
				}
			}
		}
	}
	
	/**
	 A list of peers that we have assigned a network id to, and some logic as to why.
	 */
	public class PeerNetworkGroup {
		List<PeerNode> members;
		int networkid=NO_NETWORKID;
		HashSet<Integer> forbiddenIds;
		long lastAssign;
		///True if the last call to getConsensus() found only one network id for all members of this group
		boolean unanimous;
		
		/*
		 Returns the group consensus. If no peer in this group has advertised an id, then the last-assigned id is returned.
		 As a side effect, unanimous is set if there is only one network id for all peers in this group.
		 
		 @param probabilistic if true, may return any id from the set with increased probability towards the greater consensus.
		 @todo should be explicit or weighted towards most-successful (not necessarily just 'consensus')
		 */
		int getConsensus(boolean probabilistic) {
			HashMap<Integer, Integer> h = new HashMap<Integer, Integer>();
			Integer lastId = networkid;
			synchronized (this) {
				int totalWitnesses=0;
				int maxId=networkid;
				int maxCount=0;
				for (PeerNode p : members) {
					Integer id = p.providedNetworkID;
					//Reject the advertized id which conflicts with our pre-determined boundaries (which can change)
					if (forbiddenIds.contains(id))
						continue;
					if (id == NO_NETWORKID)
						continue;
					totalWitnesses++;
					int count=1;
					Integer prev = h.get(id);
					if (prev!=null)
						count=prev.intValue()+1;
					h.put(id, count);
					if (count>maxCount) {
						maxCount=count;
						maxId=id.intValue();
					}
					lastId=id;
				}
				//Should we include ourselves in the count? Probably not, as we generally determine our network id on consensus.
				//If there is only one option everyone agrees (NO_NETWORKID is stripped out)
				unanimous=(h.size()==1);
				if (h.size()<=1)
					return lastId.intValue();
				if (!probabilistic)
					return maxId;
				/*
				 To choose a prob. network id, choose a random number between 0.0-1.0 and pick a network id such that if
				 lined up they occupy as much of the number space (0.0-1.0) as there are peers in the group to that id.
				 */
				double incrementPerWitness=1.0/totalWitnesses;
				double winningTarget=node.random.nextDouble();
				if (logMINOR) Logger.minor(this, "winningTarget="+winningTarget+", totalWitnesses="+totalWitnesses+", inc="+incrementPerWitness);
				double sum=0.0;
				for (Map.Entry<Integer, Integer> e : h.entrySet()) {
					int id = e.getKey();
					int count = e.getValue();
					sum+=count*incrementPerWitness;
					if (logMINOR) Logger.minor(this, "network "+id+" "+count+" peers, "+sum);
					if (sum>=winningTarget) {
						return id;
					}
				}
				Logger.error(this, "logic error; winningTarget="+winningTarget+", sum@end="+sum+", count="+h.size()); 
				return maxId;
			}
		}
		void assignNetworkId(int id) {
			synchronized (this) {
				this.lastAssign=System.currentTimeMillis();
				this.networkid=id;
				for (PeerNode p : members) {
					p.assignedNetworkID=id;
					p.networkGroup=this;
					try {
						p.sendFNPNetworkID(ctr);
					} catch (NotConnectedException e) {
						Logger.normal(this, "disconnected on network reassignment");
					}
				}
			}
		}
		/*
		 makes a copy of the given set of forbidden ids
		 */
		void setForbiddenIds(HashSet<Integer> a) {
			synchronized (this) {
				forbiddenIds = new HashSet<Integer>(a);
			}
		}
		/*
		 caveat, holds onto original list
		 */
		void setMembers(List<PeerNode> a) {
			synchronized (this) {
				//more correct to copy, but presently unnecessary.
				members=a;
			}
		}
		boolean recentlyAssigned() {
			return (System.currentTimeMillis()-lastAssign) < BETWEEN_PEERS;
		}
	}
	
	//List of PeerNetworkGroups ordered by priority
	List<PeerNetworkGroup> networkGroups = new ArrayList<PeerNetworkGroup>();
	
	//or zero if we don't know yet
	public int ourNetworkId = NO_NETWORKID;
	
	/**
	 * Returns true if (and only if) the connectivity between two given nodes have been computed and
	 * they have been determined to be in separate keyspace networks. Fail-safe false, if either of the
	 * two peers have been recently added, if this class is not past its initial startupChecks, etc.
	 */
	public boolean inSeparateNetworks(PeerNode a, PeerNode b) {
		if (a==null || b==null || a.assignedNetworkID == NO_NETWORKID || b.assignedNetworkID == NO_NETWORKID)
			return false;
		synchronized (transitionLock) {
			if (inTransition)
				return false;
			//NB: Object.equal's; but they should be the very same object. Neither should be null.
			return !a.networkGroup.equals(b.networkGroup);
		}
	}
	
	/**
	 * Orders PeerNetworkGroups by size largest first. Determines the priority-order in the master list.
	 * Throws on comparison of non-network-groups or those without members assigned.
	 */
	@Override
	public int compare(PeerNetworkGroup a, PeerNetworkGroup b) {
		//since we want largest-first, this is backwards of what it would normally be (a-b).
		return b.members.size()-a.members.size();
	}
	
	private final ByteCounter ctr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			node.nodeStats.networkColoringReceivedBytes(x);
		}

		@Override
		public void sentBytes(int x) {
			node.nodeStats.networkColoringSentBytes(x);
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}
		
	};
	
}
