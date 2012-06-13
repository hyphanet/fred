package freenet.node.fcp;

import freenet.io.comm.DMT;

/**
 * FCP message sent from the node to the client which includes the location reported by the endpoint.
 */
public class ProbeLocation extends FCPResponse {
	public ProbeLocation(String fcpIdentifier, double location) {
		super(fcpIdentifier);
		fs.putOverwrite(FCPMessage.IDENTIFIER, fcpIdentifier);
		fs.put(DMT.LOCATION, location);
	}

	@Override
	public String getName() {
		return "ProbeLocation";
	}
}
