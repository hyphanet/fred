package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.RequestClient;
import freenet.support.Logger;

public class FCPClientRequestClient implements RequestClient {
	
	// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
	
	public final FCPClient client;
	public final boolean forever;
	public final boolean realTimeFlag;
	
	public FCPClientRequestClient(FCPClient fcpClient, boolean forever2, boolean realTime) {
		this.client = fcpClient;
		this.forever = forever2;
		this.realTimeFlag = realTime;
	}
	
	public boolean persistent() {
		return forever;
	}
	
	public void removeFrom(ObjectContainer container) {
		if(forever)
			container.delete(this);
		else
			throw new UnsupportedOperationException();
	}
	
	public boolean objectCanDelete(ObjectContainer container) {
		container.activate(client, 1);
		if(client.isGlobalQueue) {
			Logger.error(this, "Trying to remove the RequestClient for the global queue!!!", new Exception("error"));
			return false;
		}
		return true;
	}

	public boolean realTimeFlag() {
		return realTimeFlag;
	}


}
