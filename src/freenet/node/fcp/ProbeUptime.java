package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which includes uptime information returned by the endpoint.
 */
public class ProbeUptime extends FCPResponse {
	/**
	 * @param fcpIdentifier FCP-level identifier for pairing requests and responses
	 * @param uptimePercent uptime percentage of endpoint. Depending on the type of the request this may be either
	 *                      48-hour or 7-day.
	 */
	public ProbeUptime(String fcpIdentifier, double uptimePercent) {
		super(fcpIdentifier);
		fs.put(UPTIME_PERCENT, uptimePercent);
	}

	public String getName() {
		return "ProbeUptime";
	}
}
