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
	 * @param rawError If the error is UNKNOWN, specifies remote error code. Not included otherwise.
	 * @see MHProbe.Listener onError()
	 * @see MHProbe.ProbeError
	 */
	public ProbeError(String fcpIdentifier, MHProbe.ProbeError error, Byte rawError) {
		super(fcpIdentifier);
		fs.putOverwrite(DMT.TYPE, error.name());
		if (rawError != null) fs.put(DMT.DESCRIPTION, rawError);
	}

	@Override
	public String getName() {
		return "ProbeError";
	}
}
