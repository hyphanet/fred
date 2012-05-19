package freenet.node.fcp;

import freenet.io.comm.DMT;

/**
 * FCP message sent from the node to the client which includes outgoing bandwidth limit returned by the endpoint.
 */
public class ProbeBandwidth extends FCPResponse {
	/**
	 * @param fcpIdentifier FCP-level identifier for pairing requests and responses
	 * @param outputBandwidth reported endpoint output bandwidth limit in KiB per second.
	 */
	public ProbeBandwidth(String fcpIdentifier, long outputBandwidth) {
		super(fcpIdentifier);
		fs.put(DMT.OUTPUT_BANDWIDTH_UPPER_LIMIT, outputBandwidth);
	}

	@Override
	public String getName() {
		return "ProbeBandwidth";
	}
}
