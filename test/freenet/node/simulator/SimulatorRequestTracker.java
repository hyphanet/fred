package freenet.node.simulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageType;
import freenet.io.comm.Peer;
import freenet.keys.Key;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.node.Node;
import freenet.support.Logger;

/** Tracks requests across multiple nodes in a simulation. Useful for comparing runs with different
 * code, simulation settings etc, and for statistics e.g. on path length.
 * @author toad
 */
public class SimulatorRequestTracker extends MessageDispatchSnooper {
    
    final short maxHTL;

    /** Public for API purposes, but only exposed after removing from internal structures */
    public class Request {
        final boolean isInsert;
        final boolean isSSK;
        final long uid;
        final Key key;
        /** Nodes visited in order of receiving the request */
        final List<Integer> nodeIDsVisited;
        
        Request(boolean isInsert, boolean isSSK, long uid, Key key) {
            this.isInsert = isInsert;
            this.isSSK = isSSK;
            this.uid = uid;
            this.key = key;
            this.nodeIDsVisited = new ArrayList<Integer>(maxHTL);
        }
        
        public String dump(boolean noUIDs, String prefix) {
            StringBuilder sb = new StringBuilder();
            if(!noUIDs)
                sb.append(uid).append(":\n"); // Separate line for easier diffs.
            if(isSSK) sb.append("SSK ");
            else sb.append("CHK ");
            if(isInsert) sb.append("insert");
            else sb.append("request");
            sb.append(" for ");
            sb.append(key.toString());
            sb.append("\n");
            if(prefix != null) sb.append(prefix);
            for(int n : nodeIDsVisited) {
                sb.append(n);
                sb.append(" -> ");
            }
            if(!nodeIDsVisited.isEmpty())
                sb.setLength(sb.length() - " -> ".length());
            sb.append("\n");
            return sb.toString();
        }

        public void addNode(int nodeID) {
            nodeIDsVisited.add(nodeID);
        }

        public boolean containsNode(int node) {
            return nodeIDsVisited.contains(node);
        }

        public boolean isEmpty() {
            return nodeIDsVisited.isEmpty();
        }
        
        public int count() {
            return nodeIDsVisited.size();
        }
    }
    
    private final Map<Long, Request> requestsByID;
    private final Map<Long, Request> insertsByID;
    private final Map<Key, Request[]> requestsByKey;
    private final Map<Key, Request[]> insertsByKey;

    public SimulatorRequestTracker(short htl) {
        this.maxHTL = htl;
        requestsByID = new HashMap<Long, Request>();
        insertsByID = new HashMap<Long, Request>();
        requestsByKey = new HashMap<Key, Request[]>();
        insertsByKey = new HashMap<Key, Request[]>();
    }
    
    @Override
    protected void snoopMessage(Node recipient, Message m) {
        MessageType spec = m.getSpec();
        long uid;
        boolean isSSK;
        boolean isInsert;
        Key key;
        if(spec == DMT.FNPCHKDataRequest) {
            uid = m.getLong(DMT.UID);
            isSSK = false;
            isInsert = false;
            key = (NodeCHK) m.getObject(DMT.FREENET_ROUTING_KEY);
        } else if(spec == DMT.FNPSSKDataRequest) {
            uid = m.getLong(DMT.UID);
            isSSK = true;
            isInsert = false;
            key = (NodeSSK) m.getObject(DMT.FREENET_ROUTING_KEY);
        } else if(spec == DMT.FNPInsertRequest) {
            uid = m.getLong(DMT.UID);
            isSSK = false;
            isInsert = true;
            key = (NodeCHK) m.getObject(DMT.FREENET_ROUTING_KEY);
        } else if(spec == DMT.FNPSSKInsertRequest || spec == DMT.FNPSSKInsertRequestNew) {
            uid = m.getLong(DMT.UID);
            isSSK = true;
            isInsert = true;
            key = (NodeSSK) m.getObject(DMT.FREENET_ROUTING_KEY);
        } else {
            return;
        }
        key = key.archivalCopy();
        int nodeID = getID(recipient);
        int originatorID = getPeer(m.getSource().getPeer());
        synchronized(this) {
            Request req = makeRequest(isSSK, isInsert, uid, key);
            if(req.isEmpty())
                req.addNode(originatorID);
            req.addNode(nodeID);
        }
    }

    private synchronized Request makeRequest(boolean isSSK, boolean isInsert, long uid, Key key) {
        Map<Long,Request> reqsByID = isInsert ? insertsByID : requestsByID;
        Map<Key,Request[]> reqsByKey = isInsert ? insertsByKey : requestsByKey;
        Request req = reqsByID.get(uid);
        if(req == null) {
            req = new Request(isInsert, isSSK, uid, key);
            reqsByID.put(uid, req);
            Request[] byKey = reqsByKey.get(key);
            if(byKey == null)
                byKey = new Request[] { req };
            else {
                byKey = Arrays.copyOf(byKey, byKey.length+1);
                byKey[byKey.length-1] = req;
            }
            reqsByKey.put(key, byKey);
        } else {
            assert(req.isInsert == isInsert && req.isSSK == isSSK && req.key.equals(key));
        }
        return req;
    }

    private int getID(Node node) {
        return node.getDarknetPortNumber();
    }
    
    private int getPeer(Peer peer) {
        return peer.getPort();
    }

    public synchronized Request[] dumpAndClear() {
        ArrayList<Request> reqs = new ArrayList<Request>();
        reqs.addAll(requestsByID.values());
        reqs.addAll(insertsByID.values());
        requestsByID.clear();
        insertsByID.clear();
        requestsByKey.clear();
        insertsByKey.clear();
        return reqs.toArray(new Request[reqs.size()]);
    }

    public synchronized Request[] dumpKey(Key k, boolean insert) {
        Request[] reqs = insert ? insertsByKey.get(k) : requestsByKey.get(k);
        if(reqs == null) {
            return new Request[0];
        }
        for(Request req : reqs) {
            if(insert)
                insertsByID.remove(req.uid);
            else
                requestsByID.remove(req.uid);
        }
        if(insert)
            insertsByKey.remove(k);
        else
            requestsByKey.remove(k);
        return reqs;
    }
    
    public static class RequestComparator implements Comparator<Request> {

        @Override
        public int compare(Request arg0, Request arg1) {
            int size0 = arg0.nodeIDsVisited.size();
            int size1 = arg1.nodeIDsVisited.size();
            if(size0 > size1) return 1;
            if(size1 > size0) return -1;
            LinkedList<Integer> l0 = 
                    new LinkedList<Integer>(arg0.nodeIDsVisited);
            LinkedList<Integer> l1 = 
                    new LinkedList<Integer>(arg1.nodeIDsVisited);
            while(true) {
                if(l0.isEmpty() && l1.isEmpty()) return 0;
                if(!l0.isEmpty() && l1.isEmpty()) return 1;
                if(l0.isEmpty() && !l1.isEmpty()) return -1;
                int x = l0.removeFirst();
                int y = l1.removeFirst();
                if(x > y) return 1;
                if(y > x) return -1;
            }
        }

    }

}
