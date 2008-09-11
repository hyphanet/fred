package freenet.node.simulator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertException;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.TimeUtil;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.api.Bucket;
import freenet.support.io.FileUtil;

public class BootstrapPushPullTest {

	public static int TARGET_PEERS = 10;
	public static int TEST_SIZE = 1024*1024;
	
	public static int EXIT_NO_SEEDNODES = 257;
	public static int EXIT_FAILED_TARGET = 258;
	public static int EXIT_INSERT_FAILED = 259;
	public static int EXIT_FETCH_FAILED = 260;
	public static int EXIT_THREW_SOMETHING = 261;
	
	public static void main(String[] args) throws InvalidThresholdException, IOException, NodeInitException, InterruptedException {
		try {
		String ipOverride = null;
		if(args.length > 0)
			ipOverride = args[0];
        File dir = new File("bootstrap-push-pull-test");
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
        Executor executor = new PooledExecutor();
        Node node = NodeStarter.createTestNode(5000, 5001, dir.getPath(), true, false, false, Node.DEFAULT_MAX_HTL, 0, random, executor, 1000, 5*1024*1024, true, true, true, true, true, true, true, 12*1024, false, true, ipOverride);
        //NodeCrypto.DISABLE_GROUP_STRIP = true;
    	//Logger.setupStdoutLogging(Logger.MINOR, "freenet:NORMAL,freenet.node.NodeDispatcher:MINOR,freenet.node.FNPPacketMangler:MINOR");
    	Logger.getChain().setThreshold(Logger.ERROR); // kill logging
    	// Start it
        node.start(true);
        waitForTenNodes(node);
        System.out.println("Creating test data: "+TEST_SIZE+" bytes.");
        Bucket data = node.clientCore.tempBucketFactory.makeBucket(TEST_SIZE);
        OutputStream os = data.getOutputStream();
        byte[] buf = new byte[4096];
        for(long written = 0; written < TEST_SIZE;) {
        	node.fastWeakRandom.nextBytes(buf);
        	int toWrite = (int) Math.min(TEST_SIZE - written, buf.length);
        	os.write(buf, 0, toWrite);
        	written += toWrite;
        }
        os.close();
        System.out.println("Inserting test data.");
        HighLevelSimpleClient client = node.clientCore.makeClient((short)0);
        InsertBlock block = new InsertBlock(data, new ClientMetadata(null), FreenetURI.EMPTY_CHK_URI);
        long startInsertTime = System.currentTimeMillis();
        FreenetURI uri;
        try {
			uri = client.insert(block, false, null);
		} catch (InsertException e) {
			System.err.println("INSERT FAILED: "+e);
			e.printStackTrace();
			System.exit(EXIT_INSERT_FAILED);
			return;
		}
        long endInsertTime = System.currentTimeMillis();
        System.out.println("RESULT: Insert took "+(endInsertTime-startInsertTime)+"ms ("+TimeUtil.formatTime(endInsertTime-startInsertTime)+") to "+uri+" .");
        node.park();
		
        // Bootstrap a second node.
        File secondInnerDir = new File(dir, "5002");
        secondInnerDir.mkdir();
        fis = new FileInputStream(seednodes);
        FileUtil.writeTo(fis, new File(secondInnerDir, "seednodes.fref"));
        fis.close();
        executor = new PooledExecutor();
        Node secondNode = NodeStarter.createTestNode(5002, 5003, dir.getPath(), true, false, false, Node.DEFAULT_MAX_HTL, 0, random, executor, 1000, 5*1024*1024, true, true, true, true, true, true, true, 12*1024, false, true, ipOverride);        
        secondNode.start(true);
        waitForTenNodes(secondNode);
        
        // Fetch the data
        long startFetchTime = System.currentTimeMillis();
        client = secondNode.clientCore.makeClient((short)0);
        try {
			client.fetch(uri);
		} catch (FetchException e) {
			System.err.println("FETCH FAILED: "+e);
			e.printStackTrace();
			System.exit(EXIT_FETCH_FAILED);
			return;
		}
		long endFetchTime = System.currentTimeMillis();
		System.out.println("RESULT: Fetch took "+(endFetchTime-startFetchTime)+"ms ("+TimeUtil.formatTime(endFetchTime-startFetchTime)+") of "+uri+" .");
		secondNode.park();
		System.exit(0);
	    } catch (Throwable t) {
	    	System.err.println("CAUGHT: "+t);
	    	t.printStackTrace();
	    	System.exit(EXIT_THREW_SOMETHING);
	    }
	}

	private static void waitForTenNodes(Node node) throws InterruptedException {
    	long startTime = System.currentTimeMillis();
        // Wait until we have 10 connected nodes...
        int seconds = 0;
        boolean success = false;
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
        		System.out.println("RESULT: Completed bootstrap ("+TARGET_PEERS+" peers) in "+timeTaken+"ms ("+TimeUtil.formatTime(timeTaken)+")");
        		success = true;
        		break;
        	}
        }
        if(!success) {
        	System.err.println("Failed to reach target peers count "+TARGET_PEERS+" in 10 minutes.");
        	node.park();
        	System.exit(EXIT_FAILED_TARGET);
        }
	}

}
