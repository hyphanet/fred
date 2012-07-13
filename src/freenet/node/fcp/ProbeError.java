package freenet.node.fcp;

import freenet.node.probe.Error;

/**
 * FCP message sent from the node to the client which indicates that an error has occurred.
 * These are propagated so that resources can be freed on error more quickly than they would with just a timeout.
 */
public class ProbeError extends FCPResponse {
	/**
	 * An error was received.
	 * @param fcpIdentifier Identifier: FCP-level identifier for pairing requests and responses.
	 * @param error type: The error code.
	 * @param code If error is UNKNOWN or UNRECOGNIZED_TYPE, can specify remote code. Not included otherwise.
	 * @see freenet.node.probe.Listener#onError(freenet.node.probe.Error, Byte)
	 * @see freenet.node.probe.Error
	 */
	public ProbeError(String fcpIdentifier, Error error, Byte code) {
		super(fcpIdentifier);
		fs.putOverwrite(TYPE, error.name());
		if (code != null) fs.put(CODE, code);
	}

	@Override
	public String getName() {
		return "ProbeError";
	}
}
