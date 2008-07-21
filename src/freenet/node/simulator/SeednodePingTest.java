/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import freenet.crypt.RandomSource;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.node.Announcer;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.OpennetDisabledException;
import freenet.node.SeedServerPeerNode;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.SimpleFieldSet;
import java.io.File;
import java.io.IOException;
import java.util.Vector;

/**
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class SeednodePingTest extends RealNodeTest {

    public static void main(String[] args) throws FSParseException, IOException, OpennetDisabledException, PeerParseException, InterruptedException, ReferenceSignatureVerificationException, NodeInitException, InvalidThresholdException {
        RandomSource random = NodeStarter.globalTestInit("seednode-pingtest", false, Logger.ERROR, "");
        // Create one node
        Executor executor = new PooledExecutor();
	Node node = NodeStarter.createTestNode(5000, 5001, "seednode-pingtest", true, false, false, Node.DEFAULT_MAX_HTL, 0, random, executor, 1000, 5*1024*1024, true, false, false, false, false, false, false, 0, false);		
	
	// Connect & ping
	Vector<SeedServerPeerNode> seedNodes = new Vector<SeedServerPeerNode>();
	Vector<SimpleFieldSet> seedNodesAsSFS = Announcer.readSeednodes(new File("/tmp/"));
	int numberOfNodesInTheFile = 0;
	for(SimpleFieldSet sfs : seedNodesAsSFS) {
		numberOfNodesInTheFile++;
		SeedServerPeerNode seednode = node.createNewSeedServerPeerNode(sfs);
		try {
			node.connectToSeednode(seednode);
			seedNodes.add(seednode);
		} catch (Exception fse) {
			System.out.println("ERROR adding "+seednode.toString()+ " "+fse.getMessage());
		}
	}
	if(seedNodes.size() != numberOfNodesInTheFile)
		    System.out.println("ERROR ADDING SOME OF THE SEEDNODES!!");
	System.out.println("Let some time for the "+ seedNodes.size() +" nodes to connect...");
	
	// Start it
        node.start(true);
	//Logger.setupStdoutLogging(Logger.MINOR, "freenet:NORMAL,freenet.node.FNPPacketMangler:MINOR");
	Logger.getChain().setThreshold(32); // kill logging
	Thread.sleep(10000);
	
	int pingID = 0;
	while(true) {
		int countConnectedSeednodes = 0;
		for(SeedServerPeerNode seednode : seedNodes) {
			try {
				boolean isSigValid = seednode.isSignatureVerificationSuccessfull();
				boolean isRoutingCompatible = seednode.isRoutingCompatible();
				double pingTime = seednode.averagePingTime();
				int uptime = seednode.getUptime();
				if(seednode.isRealConnection())
					continue;
				countConnectedSeednodes++;
				boolean ping = seednode.ping(pingID++);
				System.out.println(seednode.getIdentityString() + " is sigValid=" + isSigValid + " isRoutingCompat=" + isRoutingCompatible + " uptime=" + uptime + " pingTime=" + pingTime+ " uptime="+seednode.getUptime() + " ping="+ping);
			// sanity check
//			if(seednode.isRoutable())
//				System.out.println(seednode + " is routable!");
//			if(seednode.canAcceptAnnouncements())
//				System.out.println(seednode + " can't accept announcements!");
			} catch (NotConnectedException e) {
				System.out.println(seednode.getIdentityString() + " is not connected "+seednode.getHandshakeCount());
			}
		}
		System.out.println("################## ("+node.peers.countConnectedPeers()+") "+countConnectedSeednodes+'/'+node.peers.countSeednodes());
		Thread.sleep(5000);
	}
    }
}
