package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which includes link lengths reported by the endpoint.
 */
public class ProbeLinkLengths extends FCPResponse {
	public ProbeLinkLengths(String fcpIdentifier, float[] linkLengths) {
		super(fcpIdentifier);
		fs.put(LINK_LENGTHS, linkLengths);
	}

	@Override
	public String getName() {
		return "ProbeLinkLengths";
	}
}
