package freenet.node.fcp;

import freenet.io.comm.DMT;
import freenet.node.MHProbe;

/**
 * FCP message sent from the node to the client which indicates that an error has occurred.
 * These are propagated so that resources can be freed on error more quickly than they would with just a timeout.
 */
public class ProbeError extends FCPResponse {
	/**
	 * An error was received.
	 * @param fcpIdentifier Identifier: FCP-level identifier for pairing requests and responses.
	 * @param error type: The error code.
	 * @param description description: If the error is UNKNOWN, specifies remote error text. Not defined otherwise.
	 * @see MHProbe.Listener onError()
	 * @see MHProbe.ProbeError
	 */
	public ProbeError(String fcpIdentifier, MHProbe.ProbeError error, String description) {
		super(fcpIdentifier);
		fs.putOverwrite(DMT.TYPE, error.name());
		fs.putOverwrite(DMT.DESCRIPTION, description);
	}

	@Override
	public String getName() {
		return "ProbeError";
	}
}
