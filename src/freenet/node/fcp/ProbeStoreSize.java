package freenet.node.fcp;

import freenet.io.comm.DMT;

/**
 * FCP message sent from the node to the client which includes store size returned by the endpoint.
 */
public class ProbeStoreSize extends FCPResponse {
	/**
	 * @param fcpIdentifier FCP-level identifier for pairing requests and responses
	 * @param storeSize reported endpoint store size in GiB.
	 */
	public ProbeStoreSize(String fcpIdentifier, long storeSize) {
		super(fcpIdentifier);
		fs.put(DMT.STORE_SIZE, storeSize);
	}

	@Override
	public String getName() {
		return "ProbeStoreSize";
	}
}
