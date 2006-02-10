package freenet.client.events;

/**
 * Event indicating that we are attempting to compress the file.
 */
public class StartedCompressionEvent implements ClientEvent {

	public final int codec;
	
	public StartedCompressionEvent(int codec) {
		this.codec = codec;
	}
	
	static final int code = 0x08;
	
	public String getDescription() {
		return "Started compression attempt with codec "+codec;
	}

	public int getCode() {
		return code;
	}

}
