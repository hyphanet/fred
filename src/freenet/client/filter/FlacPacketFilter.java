package freenet.client.filter;

import java.io.IOException;

public class FlacPacketFilter  implements CodecPacketFilter {
	enum State {UNINITIALIZED, METADATA_FOUND};
	State currentState = State.UNINITIALIZED;
	public CodecPacket parse(CodecPacket packet) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

}
