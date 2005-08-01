package freenet.node;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.RetrievalException;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.ShortBuffer;

/**
 * @author amphibian
 * 
 * Handle an incoming insert request.
 * This corresponds to RequestHandler.
 */
public class InsertHandler implements Runnable {


    static final int DATA_INSERT_TIMEOUT = 5000;
    
    final Message req;
    final Node node;
    final long uid;
    final NodePeer source;
    final NodeCHK key;
    private short htl;
    private InsertSender sender;
    private byte[] headers;
    private BlockReceiver br;
    private Thread runThread;
    
    PartiallyReceivedBlock prb;
    
    InsertHandler(Message req, long id, Node node) {
        this.req = req;
        this.node = node;
        this.uid = id;
        this.source = (NodePeer) req.getSource();
        key = (NodeCHK) req.getObject(DMT.FREENET_ROUTING_KEY);
        htl = req.getShort(DMT.HTL);
    }
    
    public void run() {
        try {
        runThread = Thread.currentThread();
        
        // FIXME implement rate limiting or something!
        // Send Accepted
        Message accepted = DMT.createFNPAccepted(uid);
        source.send(accepted);
        
        // Source will send us a DataInsert
        
        MessageFilter mf;
        mf = MessageFilter.create().setType(DMT.FNPDataInsert).setField(DMT.UID, uid).setSource(source).setTimeout(DATA_INSERT_TIMEOUT);
        
        Message msg = node.usm.waitFor(mf);
        
        Logger.minor(this, "Received "+msg);
        
        if(msg == null) {
            Logger.error(this, "Did not receive DataInsert on "+uid+" from "+source+" !");
            Message tooSlow = DMT.createFNPRejectedTimeout(uid);
            source.sendAsync(tooSlow);
            return;
        }
        
        // We have a DataInsert
        headers = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
        // FIXME check the headers
        
        // Now create an InsertSender, or use an existing one, or
        // discover that the data is in the store.

        prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
        if(htl > 0)
            sender = node.makeInsertSender(key, htl, uid, source, headers, prb, false);
        br = new BlockReceiver(node.usm, source, uid, prb);
        
        // Receive the data, off thread
        
        Runnable dataReceiver = new DataReceiver();
        Thread t = new Thread(dataReceiver);
        t.setDaemon(true);
        t.start();

        if(htl == 0) {
            canCommit = true;
            finish();
            return;
        }
        
        // Wait...
        // What do we want to wait for?
        // If the data receive completes, that's very nice,
        // but doesn't really matter. What matters is what
        // happens to the InsertSender. If the data receive
        // fails, that does matter...
        
        // We are waiting for a terminal status on the InsertSender,
        // including REPLIED_WITH_DATA.
        // If we get transfer failed, we can check whether the receive
        // failed first. If it did it's not our fault.
        // If the receive failed, and we haven't started transferring
        // yet, we probably want to kill the sender.
        // So we call the wait method on the InsertSender, but we
        // also have a flag locally to indicate the receive failed.
        // And if it does, we interrupt.
        
        while(true) {
            synchronized(sender) {
                try {
                    sender.wait(5000);
                } catch (InterruptedException e) {
                    // Cool, probably this is because the receive failed...
                }
            }
            if(receiveFailed) {
                // Cancel the sender
                sender.receiveFailed(); // tell it to stop if it hasn't already failed... unless it's sending from store
                // Nothing else we can do
                return;
            }
            
            int status = sender.getStatus();
            
            if(status == InsertSender.NOT_FINISHED) {
                continue;
            }
            
            if(status == InsertSender.REJECTED_OVERLOAD) {
                msg = DMT.createFNPRejectedOverload(uid);
                source.send(msg);
                return;
            }
            
            if(status == InsertSender.ROUTE_NOT_FOUND) {
                msg = DMT.createFNPRouteNotFound(uid, sender.getHTL());
                source.send(msg);
                canCommit = true;
                finish();
                return;
            }
            
            if(status == InsertSender.SUCCESS) {
                canCommit = true;
                finish();
                return;
            }
        }
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
        } finally {
            Logger.minor(this, "Exiting InsertHandler.run() for "+uid);
            node.unlockUID(uid);
        }
    }

    private boolean canCommit = false;
    
    /**
     * If canCommit, and we have received all the data, and it
     * verifies, then commit it.
     */
    private void finish() {
        Message toSend = null;
        synchronized(this) { // REDFLAG do not use synch(this) for any other purpose!
            if(!canCommit) return;
            if(!prb.allReceived()) return;
            try {
                CHKBlock block = new CHKBlock(prb.getBlock(), headers, key);
                node.store(block);
            } catch (CHKVerifyException e) {
                Logger.error(this, "Verify failed in InsertHandler: "+e+" - headers: "+HexUtil.bytesToHex(headers), e);
                toSend = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_VERIFY_FAILED);
            }
        }
        if(toSend != null) {
            source.sendAsync(toSend);
        } else {
            // Succeeded! Yay!
            Message msg = DMT.createFNPInsertReply(uid);
            source.send(msg);
        }
    }
    
    /** Has the receive failed? If so, there's not much more that can be done... */
    private boolean receiveFailed;

    public class DataReceiver implements Runnable {

        public void run() {
            Logger.minor(this, "Receiving data for "+InsertHandler.this);
            try {
                br.receive();
                Logger.minor(this, "Received data for "+InsertHandler.this);
                finish();
            } catch (RetrievalException e) {
                receiveFailed = true;
                runThread.interrupt();
                Message msg = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_RECEIVE_FAILED);
                source.send(msg);
                Logger.minor(this, "Failed to retrieve: "+e, e);
                return;
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t, t);
            }
        }

    }

}
