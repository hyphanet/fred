package freenet.node;

import java.util.Vector;

import freenet.crypt.RandomSource;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSKBlock;
import freenet.keys.KeyBlock;
import freenet.support.DoublyLinkedList;
import freenet.support.UpdatableSortedLinkedListItemImpl;

/**
 * Interface to clients for starting a request.
 * Also represents a single client for fairness purposes.
 */
public class RequestStarterClient extends UpdatableSortedLinkedListItemImpl {

	final int priority;
	private int random;
	private long cycleLastSent;
	private final Vector requests;
	private final RandomSource rs;
	private final QueueingSimpleLowLevelClient client;
	private final RequestStarter starter;

	public RequestStarterClient(short prioClass, short prio, RandomSource r, QueueingSimpleLowLevelClient c, RequestStarter starter) {
		this((prioClass << 16) + prio, r, c, starter);
	}
	
	private RequestStarterClient(int prio, RandomSource r, QueueingSimpleLowLevelClient c, RequestStarter starter) {
		priority = prio;
		this.random = r.nextInt();
		this.starter = starter;
		this.cycleLastSent = -1;
		this.requests = new Vector();
		this.rs = r;
		this.client = c;
		starter.registerClient(this);
	}
	
	/**
	 * Blocking fetch of a key.
	 * @throws LowLevelGetException If the fetch failed for some reason.
	 */
	public ClientKeyBlock getKey(ClientKey key, boolean localOnly, boolean cache, boolean ignoreStore) throws LowLevelGetException {
		QueuedDataRequest qdr = new QueuedDataRequest(key, localOnly, cache, client, ignoreStore);
		addRequest(qdr);
		return qdr.waitAndFetch();
	}
	
	/**
	 * Blocking insert of a key.
	 * @throws LowLevelPutException If the fetch failed for some reason.
	 */
	public void putKey(ClientKeyBlock block, boolean cache) throws LowLevelPutException {
		QueuedInsertRequest qir = new QueuedInsertRequest(block, client, cache);
		addRequest(qir);
		qir.waitAndPut();
	}
	
	void addRequest(QueuedRequest qr) {
		synchronized(this) {
			requests.add(qr);
		}
		if(starter != null)
			starter.notifyReady(this);
	}
	
	public long getCycleLastSent() {
		return cycleLastSent;
	}

	private DoublyLinkedList parentList;
	
	public DoublyLinkedList getParent() {
		return parentList;
	}

	public DoublyLinkedList setParent(DoublyLinkedList l) {
		DoublyLinkedList oldList = parentList;
		parentList = l;
		return oldList;
	}

	public int compareTo(Object o) {
		if(this == o) return 0;
		RequestStarterClient c = (RequestStarterClient) o;
		if(priority > c.priority) return 1;
		if(priority < c.priority) return -1;
		if(random > c.random) return 1;
		if(random < c.random) return -1;
		return 0;
	}

	public synchronized boolean isReady() {
		return !requests.isEmpty();
	}

	public boolean send(long cycleNumber) {
		QueuedRequest qr;
		synchronized(this) {
			if(!requests.isEmpty()) {
				int x = rs.nextInt(requests.size());
				qr = (QueuedRequest) requests.remove(x);
			} else qr = null;
		}
		if(qr == null) return false;
		qr.clearToSend();
		return true;
	}

	public void setCycleLastSet(long cycleNumber) {
		this.cycleLastSent = cycleNumber;
	}

}
