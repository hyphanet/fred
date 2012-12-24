package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which indicates that the endpoint has opted not to respond to the
 * request type. This is opposed to just forwarding it again or letting it time out, which would bias the endpoints
 */
public class ProbeRefused extends FCPResponse {
	public ProbeRefused(String fcpIdentifier) {
		super(fcpIdentifier);
	}

	@Override
	public String getName() {
		return "ProbeRefused";
	}
}
