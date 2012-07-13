package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which includes an endpoint identifier and uptime information.
 */
public class ProbeIdentifier extends FCPResponse {
	/**
	 * @param fcpIdentifier FCP-level identifier for pairing requests and responses
	 * @param probeIdentifier probe endpoint identifier
	 * @param uptimePercentage 7-day uptime percentage
	 */
	public ProbeIdentifier(String fcpIdentifier, long probeIdentifier, long uptimePercentage) {
		super(fcpIdentifier);
		fs.put(PROBE_IDENTIFIER, probeIdentifier);
		fs.put(UPTIME_PERCENT, uptimePercentage);
	}

	@Override
	public String getName() {
		return "ProbeIdentifier";
	}
}
