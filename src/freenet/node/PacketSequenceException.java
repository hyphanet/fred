package freenet.node;

import freenet.io.comm.LowLevelFilterException;

public class PacketSequenceException extends LowLevelFilterException {
	private static final long serialVersionUID = -1;

	public PacketSequenceException(String string) {
		super(string);
	}

}
