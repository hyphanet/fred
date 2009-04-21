package freenet.client.events;

public class ExpectedFileSizeEvent implements ClientEvent {
	
	public final long expectedSize;
	
	public ExpectedFileSizeEvent(long size) {
		expectedSize = size;
	}
	
	static final int CODE = 0x0C;

	public int getCode() {
		return CODE;
	}

	public String getDescription() {
		return "Expected file size: "+expectedSize;
	}

}
