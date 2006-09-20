package freenet.client.events;

import freenet.keys.ClientKey;

public class DecodedBlockEvent implements ClientEvent {

	public static final int code = 0x03;
	public final ClientKey key; 
	
	public DecodedBlockEvent(ClientKey key) {
		this.key = key;
	}

	public String getDescription() {
		return "Decoded a block of data: "+key.getURI();
	}

	public int getCode() {
		return code;
	}

}
