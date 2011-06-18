package freenet.client.events;

public class ExpectedFileSizeEvent implements ClientEvent {
	
	public final long expectedSize;
	
	public ExpectedFileSizeEvent(long size) {
		expectedSize = size;
	}
	
	static final int CODE = 0x0C;

	@Override
	public int getCode() {
		return CODE;
	}

	@Override
	public String getDescription() {
		return "Expected file size: "+expectedSize;
	}

}
