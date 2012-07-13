package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which includes build / main version returned by the endpoint.
 */
public class ProbeBuild extends FCPResponse {
	/**
	 * @param fcpIdentifier FCP-level identifier for pairing requests and responses
	 * @param build build / main version of endpoint
	 */
	public ProbeBuild(String fcpIdentifier, int build) {
		super(fcpIdentifier);
		fs.put(BUILD, build);
	}

	@Override
	public String getName() {
		return "ProbeBuild";
	}
}
