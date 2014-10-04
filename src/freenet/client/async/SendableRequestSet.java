package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;

public interface SendableRequestSet {
    
    public SendableRequest[] listRequests(ObjectContainer container);
    
    public boolean addRequest(SendableRequest req, ObjectContainer container);
    
    public boolean removeRequest(SendableRequest req, ObjectContainer container);

    public void removeFrom(ObjectContainer container);

}
