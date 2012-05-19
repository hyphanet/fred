package freenet.node.fcp;

import freenet.io.comm.DMT;

/**
 * FCP message sent from the node to the client which includes an endpoint identifier and uptime information.
 */
public class ProbeIdentifier extends FCPResponse {
	/**
	 * @param fcpIdentifier FCP-level identifier for pairing requests and responses
	 * @param identifier probe endpoint identifier
	 * @param uptimePercentage 7-day uptime percentage
	 */
	public ProbeIdentifier(String fcpIdentifier, long identifier, long uptimePercentage) {
		super(fcpIdentifier);
		fs.put(DMT.IDENTIFIER, identifier);
		fs.put(DMT.UPTIME_PERCENT, uptimePercentage);
	}

	@Override
	public String getName() {
		return "ProbeIdentifier";
	}
}
