/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;

import freenet.crypt.DummyRandomSource;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.node.FSParseException;
import freenet.node.Location;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.PeerNode;
import freenet.node.DarknetPeerNode.FRIEND_TRUST;
import freenet.node.SyncSendWaitedTooLongException;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.math.BootstrappingDecayingRunningAverage;
import freenet.support.math.RunningAverage;

/**
 * @author amphibian
 * 
 * Create a mesh of nodes
 * Connect them in a s.w. network (rather than just letting them sort out their locations)
 * Then run some cross-peer challenge/response pings.
 */
public class RealNodeSecretPingTest {

    //static final int NUMBER_OF_NODES = 150;
	static final int NUMBER_OF_NODES = 15;
    static final short MAX_HTL = (short)6;
	static final int DEGREE = 5;
	
	static final short PING_HTL = 6;
	static final short DAWN_HTL = 4;
	static final int SECRETPONG_TIMEOUT=5000;
	static final long storeSize = 1024*1024;
	
	static final FRIEND_TRUST trust = FRIEND_TRUST.LOW;

	public static int DARKNET_PORT_BASE = RealNodeRoutingTest.DARKNET_PORT_END;
	public static final int DARKNET_PORT_END = DARKNET_PORT_BASE + NUMBER_OF_NODES;
	
    public static void main(String[] args) throws FSParseException, PeerParseException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException {
        //Logger.setupStdoutLogging(LogLevel.NORMAL, "freenet.node.CPUAdjustingSwapRequestInterval:minor" /*"freenet.node.LocationManager:debug,freenet.node.FNPPacketManager:normal,freenet.io.comm.MessageCore:debug"*/);
        System.out.println("SecretPing (CRAM) test using real nodes:");
        System.out.println();
        String wd = "realNodeSecretPingTest";
        new File(wd).mkdir();
        //NOTE: globalTestInit returns in ignored random source
        NodeStarter.globalTestInit(wd, false, LogLevel.ERROR, "freenet.node.Location:normal,freenet.node.simulator.RealNodeSecretPingTest:normal,freenet.node.NetworkIDManager:normal", true);

        DummyRandomSource random = new DummyRandomSource();
        //DiffieHellman.init(random);
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        Executor executor = new PooledExecutor();
		
		//Allow secret pings, but don't automatically send them (this is the test for them!)
		freenet.node.NetworkIDManager.disableSecretPings=false;
		freenet.node.NetworkIDManager.disableSecretPinger=true;
		
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i] = 
            	NodeStarter.createTestNode(DARKNET_PORT_BASE+i, 0, wd, true, MAX_HTL, 0 /* no dropped packets */, random, executor, 500*NUMBER_OF_NODES, storeSize, true, true, false, false, false, true, true, 0, true, false, true, false, null);
            Logger.normal(RealNodeRoutingTest.class, "Created node "+i);
        }
        Logger.normal(RealNodeRoutingTest.class, "Created "+NUMBER_OF_NODES+" nodes");
        // Now link them up
        makeKleinbergNetwork(nodes);
        Logger.normal(RealNodeRoutingTest.class, "Added small-world links");
        
        for(int i=0;i<NUMBER_OF_NODES;i++)
            nodes[i].start(false);
		
        // Now sit back and watch the fireworks!
        int cycleNumber = 0;
        RunningAverage avg2 = new BootstrappingDecayingRunningAverage(0.0, 0.0, 1.0, 100, null);
        while(true) {
            cycleNumber++;
			
			try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                // Ignore
            }
			
			Node source = nodes[random.nextInt(NUMBER_OF_NODES)];
			PeerNode verify = source.peers.getRandomPeer();
			PeerNode pathway = source.peers.getRandomPeer(verify);
			
			Logger.error(source, "verify ("+getPortNumber(verify)+") through: "+getPortNumber(pathway)+"; so far "+avg2.currentValue());
			
			long uid=random.nextLong();
			long secret=random.nextLong();
			
			if (verify==null) {
				Logger.error(source, "verify peernode is null");
				continue;
			}
			
			if (pathway==null) {
				Logger.error(source, "pathway peernode is null");
				continue;
			}
			
			try {
				//Send the FNPStoreSecret message to the 'verify' node
				verify.sendSync(DMT.createFNPStoreSecret(uid, secret), null, false);
				
				if (!getAck(source, verify, uid)) {
					Logger.error(source, "did not get storesecret ack for "+uid);
					avg2.report(0.0);
					continue;
				}
				
				//Send the request for the secret through the 'pathway' node.
				pathway.sendSync(DMT.createFNPSecretPing(uid, verify.getLocation(), PING_HTL, DAWN_HTL, 0, verify.getIdentity()), null, false);
				
				long result=getSecretPingResponse(source, pathway, uid);
				if (result!=secret) {
					Logger.error(source, "not matched: "+secret+" != "+result);
					avg2.report(0.0);
				} else {
					Logger.error(source, "match: "+secret);
					avg2.report(1.0);
				}
			} catch (NotConnectedException e) {
				Logger.error(source, "what?",e);
				avg2.report(0.0);
			} catch (DisconnectedException e) {
				Logger.error(source, "huh?",e);
				avg2.report(0.0);
			} catch (SyncSendWaitedTooLongException e) {
				Logger.error(source, "eh?", e);
				avg2.report(0.0);
			}
        }
    }
	
	private static boolean getAck(Node source, PeerNode pathway, long uid) throws DisconnectedException {
		//wait for an accepted
		MessageFilter mfAccepted = MessageFilter.create().setSource(pathway).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPAccepted);
		Message msg = source.getUSM().waitFor(mfAccepted, null);
		
		if (msg==null) {
			return false;
		}
		
		if (msg.getSpec() == DMT.FNPAccepted) {
			return true;
		}
		
		Logger.error(source, "got "+msg);
		return false;
	}
	
	private static long getSecretPingResponse(Node source, PeerNode pathway, long uid) throws DisconnectedException {
		//wait for a reject or pong
		MessageFilter mfPong = MessageFilter.create().setSource(pathway).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPSecretPong);
		MessageFilter mfRejectLoop = MessageFilter.create().setSource(pathway).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPRejectedLoop);
		Message msg = source.getUSM().waitFor(mfPong.or(mfRejectLoop), null);
		
		if (msg==null) {
			Logger.error(source, "fatal timeout in waiting for secretpong from "+getPortNumber(pathway));
			return -2;
		}
		
		if (msg.getSpec() == DMT.FNPSecretPong) {
			int suppliedCounter=msg.getInt(DMT.COUNTER);
			long secret=msg.getLong(DMT.SECRET);
			Logger.normal(source, "got secret, counter="+suppliedCounter);
			return secret;
		}
		
		if (msg.getSpec() == DMT.FNPRejectedLoop) {
			Logger.error(source, "top level secret ping should not reject!: "+getPortNumber(source)+" -> "+getPortNumber(pathway));
			return -1;
		}
		
		return -3;
	}
	
	/*
	 Borrowed from mrogers simulation code (February 6, 2008)
	 */
	static void makeKleinbergNetwork (Node[] nodes)
	{
		for (int i=0; i<nodes.length; i++) {
			Node a = nodes[i];
			// Normalise the probabilities
			double norm = 0.0;
			for (int j=0; j<nodes.length; j++) {
				Node b = nodes[j];
				if (a.getLocation() == b.getLocation()) continue;
				norm += 1.0 / distance (a, b);
			}
			// Create DEGREE/2 outgoing connections
			for (int k=0; k<nodes.length; k++) {
				Node b = nodes[k];
				if (a.getLocation() == b.getLocation()) continue;
				double p = 1.0 / distance (a, b) / norm;
				for (int n = 0; n < DEGREE / 2; n++) {
					if (Math.random() < p) {
						try {
							a.connect (b, trust);
							b.connect (a, trust);
						} catch (FSParseException e) {
							Logger.error(RealNodeSecretPingTest.class, "cannot connect!!!!", e);
						} catch (PeerParseException e) {
							Logger.error(RealNodeSecretPingTest.class, "cannot connect #2!!!!", e);
						} catch (freenet.io.comm.ReferenceSignatureVerificationException e) {
							Logger.error(RealNodeSecretPingTest.class, "cannot connect #3!!!!", e);
						}
						break;
					}
				}
			}
		}
	}
	
	static double distance(Node a, Node b) {
		double aL=a.getLocation();
		double bL=b.getLocation();
		return Location.distance(aL, bL);
	}
	
	static String getPortNumber(PeerNode p) {
		if (p == null || p.getPeer() == null)
			return "null";
		return Integer.toString(p.getPeer().getPort());
	}
	
	static String getPortNumber(Node n) {
		if (n == null)
			return "null";
		return Integer.toString(n.getDarknetPortNumber());
	}
	
}
