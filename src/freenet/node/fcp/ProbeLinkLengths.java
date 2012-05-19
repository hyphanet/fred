package freenet.node.fcp;

import freenet.io.comm.DMT;

/**
 * FCP message sent from the node to the client which includes link lengths reported by the endpoint.
 */
public class ProbeLinkLengths extends FCPResponse {
	public ProbeLinkLengths(String fcpIdentifier, double[] linkLengths) {
		super(fcpIdentifier);
		fs.putOverwrite(FCPMessage.IDENTIFIER, fcpIdentifier);
		fs.put(DMT.LINK_LENGTHS, linkLengths);
	}

	@Override
	public String getName() {
		return "ProbeLinkLengths";
	}
}
