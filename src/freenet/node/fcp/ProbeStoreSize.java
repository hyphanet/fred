package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which includes store size returned by the endpoint.
 */
public class ProbeStoreSize extends FCPResponse {
	/**
	 * @param fcpIdentifier FCP-level identifier for pairing requests and responses
	 * @param storeSize reported endpoint store size in GiB multiplied by Gaussian noise.
	 */
	public ProbeStoreSize(String fcpIdentifier, float storeSize) {
		super(fcpIdentifier);
		fs.put(STORE_SIZE, storeSize);
	}

	@Override
	public String getName() {
		return "ProbeStoreSize";
	}
}
