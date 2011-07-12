package freenet.client.events;

public class ExpectedMIMEEvent implements ClientEvent {
	
	static final int CODE = 0x0B;
	
	public final String expectedMIMEType;
	
	public ExpectedMIMEEvent(String type) {
		this.expectedMIMEType = type;
	}

	@Override
	public int getCode() {
		return CODE;
	}

	@Override
	public String getDescription() {
		return "Expected MIME type: "+expectedMIMEType;
	}

}
