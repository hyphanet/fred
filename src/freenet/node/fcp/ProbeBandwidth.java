package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which includes outgoing bandwidth limit returned by the endpoint.
 */
public class ProbeBandwidth extends FCPResponse {
	/**
	 * @param fcpIdentifier FCP-level identifier for pairing requests and responses
	 * @param outputBandwidth reported endpoint output bandwidth limit in KiB per second.
	 */
	public ProbeBandwidth(String fcpIdentifier, float outputBandwidth) {
		super(fcpIdentifier);
		fs.put(OUTPUT_BANDWIDTH, outputBandwidth);
	}

	@Override
	public String getName() {
		return "ProbeBandwidth";
	}
}
