package freenet.node;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.keys.NodeCHK;
import freenet.node.states.DataRequestServerHandler;
import freenet.support.LRUQueue;

/**
 * Tracks running chains and their DataRequestServerHandler's,
 * for coalescing and other purposes.
 * Also tracks old chain IDs for purpose of loop detection.
 */
public class ChainManager {
    
    final LRUQueue oldIDs;
    final HashMap currentChains;
    final int maxOldIDs;
    final HashMap currentChainsByKey;

    ChainManager(int maxOldIDs) {
        oldIDs = new LRUQueue();
        currentChains = new HashMap();
        this.maxOldIDs = maxOldIDs;
        currentChainsByKey = new HashMap();
    }
    
    synchronized Chain getChain(long id) {
        return (Chain) currentChains.get(new Long(id));
    }

    /**
     * Have we seen the ID recently? 
     */
    public synchronized boolean checkOldIDs(long id) {
        return oldIDs.contains(new Long(id));
    }

    /**
     * Add an ID to the list of known chain IDs.
     */
    public synchronized void addOldID(long id) {
        oldIDs.push(new Long(id));
        while(oldIDs.size() > maxOldIDs)
            oldIDs.pop();
    }

    /**
     * Get a currently running chain by key/htl.
     * @param key The key to search for.
     * @param htl The HTL to search for. Must be an exact match.
     * If we match on a higher HTL, there are timeout problems,
     * and if we match on a lower HTL, the request will be degraded.
     * UNLESS the chain is actually transferring, in which case we
     * ignore the HTL.
     * @return
     */
    public synchronized Chain getChain(NodeCHK key, int htl) {
        LinkedList l = (LinkedList) currentChainsByKey.get(key);
        if(l == null) return null;
        for(Iterator i = l.listIterator();i.hasNext();) {
            Chain c = (Chain) i.next();
            if(c.fetcher.getHTL() == htl) return c;
            if(c.fetcher.isTransferring()) return c;
        }
        return null;
    }

    /**
     * Add a new Chain to the list of live chains.
     * @param id The unique ID of the message chain.
     * @param key The key being fetched.
     * @param sh The DataRequestServerHandler fetching it.
     */
    public synchronized void add(long id, NodeCHK key, DataRequestServerHandler sh, PeerNode source) {
        Chain c = new Chain(id, sh, source);
        currentChains.put(new Long(id), sh);
        LinkedList l = (LinkedList) currentChainsByKey.get(key);
        if(l == null) {
            l = new LinkedList();
            currentChainsByKey.put(key, l);
        }
        l.add(c);
    }
}
