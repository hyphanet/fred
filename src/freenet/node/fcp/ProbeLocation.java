package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which includes the location reported by the endpoint.
 */
public class ProbeLocation extends FCPResponse {
	public ProbeLocation(String fcpIdentifier, double location) {
		super(fcpIdentifier);
		fs.put(LOCATION, location);
	}

	@Override
	public String getName() {
		return "ProbeLocation";
	}
}
