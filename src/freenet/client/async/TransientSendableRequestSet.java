package freenet.client.async;

import java.util.HashSet;

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;

/**
 * Since we don't need to worry about activation, we can simply use a HashSet.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class TransientSendableRequestSet implements SendableRequestSet {

	private final HashSet<SendableRequest> set;
	
	TransientSendableRequestSet() {
		set = new HashSet<SendableRequest>();
	}
	
	public synchronized boolean addRequest(SendableRequest req, ObjectContainer container) {
		return set.add(req);
	}

	public synchronized SendableRequest[] listRequests(ObjectContainer container) {
		return set.toArray(new SendableRequest[set.size()]);
	}

	public boolean removeRequest(SendableRequest req, ObjectContainer container) {
		return set.remove(req);
	}

}
