package freenet.node.simulator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import freenet.crypt.RandomSource;
import freenet.node.Node;
import freenet.node.NodeCrypto;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.TimeUtil;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.io.FileUtil;

public class BootstrapSeedTest {

	public static int TARGET_PEERS = 10;
	public static int EXIT_NO_SEEDNODES = 257;
	public static int EXIT_FAILED_TARGET = 258;
	
	/**
	 * @param args
	 * @throws InvalidThresholdException 
	 * @throws NodeInitException 
	 * @throws InterruptedException 
	 * @throws IOException 
	 */
	public static void main(String[] args) throws InvalidThresholdException, NodeInitException, InterruptedException, IOException {
        File dir = new File("bootstrap-test");
        FileUtil.removeAll(dir);
        RandomSource random = NodeStarter.globalTestInit(dir.getPath(), false, Logger.ERROR, "");
        File seednodes = new File("seednodes.fref");
        if(!seednodes.exists() || seednodes.length() == 0 || !seednodes.canRead()) {
        	System.err.println("Unable to read seednodes.fref, it doesn't exist, or is empty");
        	System.exit(EXIT_NO_SEEDNODES);
        }
        File innerDir = new File(dir, "5000");
        innerDir.mkdir();
        FileInputStream fis = new FileInputStream(seednodes);
        FileUtil.writeTo(fis, new File(innerDir, "seednodes.fref"));
        fis.close();
        // Create one node
        NodeCrypto.DISABLE_GROUP_STRIP = true;
        Executor executor = new PooledExecutor();
        Node node = NodeStarter.createTestNode(5000, 5001, "bootstrap-test", true, false, false, Node.DEFAULT_MAX_HTL, 0, random, executor, 1000, 5*1024*1024, true, true, true, true, true, true, true, 12*1024, false, true);
        //NodeCrypto.DISABLE_GROUP_STRIP = true;
    	//Logger.setupStdoutLogging(Logger.MINOR, "freenet:NORMAL,freenet.node.NodeDispatcher:MINOR,freenet.node.FNPPacketMangler:MINOR");
    	Logger.getChain().setThreshold(Logger.ERROR); // kill logging
    	long startTime = System.currentTimeMillis();
    	// Start it
        node.start(true);
        // Wait until we have 10 connected nodes...
        int seconds = 0;
        while(seconds < 600) {
        	Thread.sleep(1000);
        	int seeds = node.peers.countSeednodes();
        	int seedConns = node.peers.getConnectedSeedServerPeersVector(null).size();
        	int opennetPeers = node.peers.countValidPeers();
        	int opennetConns = node.peers.countConnectedOpennetPeers();
        	System.err.println(""+seconds+" : seeds: "+seeds+", connected: "+seedConns
        			+" opennet: peers: "+opennetPeers+", connected: "+opennetConns);
        	seconds++;
        	if(opennetConns >= TARGET_PEERS) {
        		long timeTaken = System.currentTimeMillis()-startTime;
        		System.out.println("Completed bootstrap ("+TARGET_PEERS+" peers) in "+timeTaken+"ms ("+TimeUtil.formatTime(timeTaken)+")");
        		node.park();
        		System.exit(0);
        	}
        }
        System.err.println("Failed to reach target peers count "+TARGET_PEERS+" in 5 minutes.");
		node.park();
        System.exit(EXIT_FAILED_TARGET);
	}

}
