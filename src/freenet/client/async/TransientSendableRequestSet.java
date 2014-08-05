package freenet.client.async;

import java.util.HashSet;

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
	
	@Override
	public synchronized boolean addRequest(SendableRequest req) {
		return set.add(req);
	}

	@Override
	public synchronized SendableRequest[] listRequests() {
		return set.toArray(new SendableRequest[set.size()]);
	}

	@Override
	public synchronized boolean removeRequest(SendableRequest req) {
		return set.remove(req);
	}

}
