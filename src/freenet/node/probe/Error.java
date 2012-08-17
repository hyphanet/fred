package freenet.node.probe;

public enum Error {
	/**
	 * The node being waited on to provide a response disconnected.
	 */
	DISCONNECTED((byte) 0),
	/**
	 * A node cannot accept the request because its probe DoS protection has tripped.
	 */
	OVERLOAD((byte) 1),
	/**
	 * Timed out while waiting for a response.
	 */
	TIMEOUT((byte) 2),
	/**
	 * An error occurred, but a node did not recognize the error used.
	 * If this occurs locally, it will be specified along with the unrecognized code.
	 */
	UNKNOWN((byte) 3),
	/**
	 * A remote node did not recognize the requested probe type. For locally started probes it will not be
	 * a ProbeError but a ProtocolError.
	 */
	UNRECOGNIZED_TYPE((byte) 4),
	/**
	 * A node received and understood the request, but failed to forward it to another node.
	 * @see freenet.node.probe.Probe#MAX_SEND_ATTEMPTS
	 */
	CANNOT_FORWARD((byte) 5);

	/**
	 * Stable numerical value to represent the enum value. Used to send over the network instead of .name().
	 * Ordinals are not acceptable because they rely on the number and ordering of enums.
	 */
	public final byte code;

	private static final int MAX_CODE = Error.values().length;

	Error(byte code) {
		this.code = code;
	}

	/**
	 * Checks whether valueOf() will throw for the given code. Intended to make things more concise and
	 * faster than try-catch blocks.
	 *
	 * @param code to be converted to an enum value.
	 * @return true if the code can be converted to an enum value; false if not.
	 */
	static boolean isValid(byte code) {
		//Assumes codes are consecutive, start at zero, and all are valid.
		return code >= 0 && code < MAX_CODE;
	}

	/**
	 * Determines the enum value with the given code.
	 *
	 * @param code enum value code.
	 * @return enum value with selected code.
	 * @throws IllegalArgumentException There is no enum value with the requested code.
	 */
	static Error valueOf(byte code) throws IllegalArgumentException {
		switch (code) {
			case 0:
				return DISCONNECTED;
			case 1:
				return OVERLOAD;
			case 2:
				return TIMEOUT;
			case 3:
				return UNKNOWN;
			case 4:
				return UNRECOGNIZED_TYPE;
			case 5:
				return CANNOT_FORWARD;
			default:
				throw new IllegalArgumentException("There is no ProbeError with code " + code + ".");
		}
	}
}
