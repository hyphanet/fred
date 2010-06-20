package freenet.client.events;

import freenet.crypt.HashResult;

public class ExpectedHashesEvent implements ClientEvent {

	public final HashResult[] hashes;
	
	public final static int CODE = 0x0E;
	
	public ExpectedHashesEvent(HashResult[] h) {
		hashes = h;
	}

	public int getCode() {
		return CODE;
	}

	public String getDescription() {
		return "Expected hashes";
	}

}
