package freenet.client.async;

import freenet.node.SendableRequest;

public interface SendableRequestSet {
	
	public SendableRequest[] listRequests();
	
	public boolean addRequest(SendableRequest req);
	
	public boolean removeRequest(SendableRequest req);

}
