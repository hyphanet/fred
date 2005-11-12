package freenet.node;

import freenet.io.comm.LowLevelFilterException;

public class PacketSequenceException extends LowLevelFilterException {

	public PacketSequenceException(String string) {
		super(string);
	}

}
