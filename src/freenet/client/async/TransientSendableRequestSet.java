package freenet.client.async;

import java.util.HashSet;

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;
import freenet.support.Logger;

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

	public synchronized boolean removeRequest(SendableRequest req, ObjectContainer container) {
		return set.remove(req);
	}

	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing TransientSendableRequestSet in database", new Exception("error"));
		return false;
	}

}
