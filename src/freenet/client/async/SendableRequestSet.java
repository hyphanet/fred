package freenet.client.async;

import java.io.Serializable;
import java.util.HashSet;

import freenet.node.SendableRequest;

/**
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class SendableRequestSet implements Serializable {

    private static final long serialVersionUID = 1L;
    private final HashSet<SendableRequest> set;
	
	SendableRequestSet() {
		set = new HashSet<SendableRequest>();
	}
	
	public synchronized boolean addRequest(SendableRequest req) {
		return set.add(req);
	}

	public synchronized SendableRequest[] listRequests() {
		return set.toArray(new SendableRequest[set.size()]);
	}

	public synchronized boolean removeRequest(SendableRequest req) {
		return set.remove(req);
	}

}
