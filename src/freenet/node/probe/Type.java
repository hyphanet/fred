package freenet.node.probe;

/**
 * Describes the result type requested by the originating node.
 */
public enum Type {
	BANDWIDTH((byte)0),
	BUILD((byte)1),
	IDENTIFIER((byte)2),
	LINK_LENGTHS((byte)3),
	LOCATION((byte)4),
	STORE_SIZE((byte)5),
	UPTIME_48H((byte)6),
	UPTIME_7D((byte)7);

	public final byte code;

	private static final int MAX_CODE = Type.values().length;

	Type(byte code) { this.code = code; }

	/**
	 * Checks whether valueOf() will throw for the given code. Intended to make things more concise and
	 * faster than try-catch blocks.
	 * @param code to be converted to an enum value.
	 * @return true if the code can be converted to an enum value; false if not.
	 */
	static boolean isValid(byte code) {
		return code >= 0 && code < MAX_CODE;
	}

	/**
	 * Determines the enum value with the given code.
	 * @param code enum value code.
	 * @return enum value with selected code.
	 * @throws IllegalArgumentException There is no enum value with the requested code.
	 */
	static Type valueOf(byte code) throws IllegalArgumentException {
		switch (code) {
			case 0: return BANDWIDTH;
			case 1: return BUILD;
			case 2: return IDENTIFIER;
			case 3: return LINK_LENGTHS;
			case 4: return LOCATION;
			case 5: return STORE_SIZE;
			case 6: return UPTIME_48H;
			case 7: return UPTIME_7D;
			default: throw new IllegalArgumentException("There is no ProbeType with code " + code + ".");
		}
	}
}
