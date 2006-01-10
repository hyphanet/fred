package freenet.client.events;

import freenet.keys.ClientKey;
import freenet.keys.Key;

public class SimpleBlockPutEvent implements ClientEvent {

	public final static int code = 0x04;
	
	private final ClientKey key;
	
	public SimpleBlockPutEvent(ClientKey key) {
		this.key = key;
	}

	public String getDescription() {
		return "Inserting simple key: "+key.getURI();
	}

	public int getCode() {
		return code;
	}

}
