package freenet.clients.fcp;

import freenet.node.RequestClient;

public class FCPClientRequestClient implements RequestClient {
	
	public final PersistentRequestClient client;
	public final boolean forever;
	public final boolean realTimeFlag;
	
	public FCPClientRequestClient(PersistentRequestClient fcpClient, boolean forever2, boolean realTime) {
		this.client = fcpClient;
		this.forever = forever2;
		this.realTimeFlag = realTime;
	}
	
	@Override
	public boolean persistent() {
		return forever;
	}
	
	@Override
	public boolean realTimeFlag() {
		return realTimeFlag;
	}


}
