package freenet.client.events;

public class SendingToNetworkEvent implements ClientEvent {
	
	final static int CODE = 0x0A;

	@Override
	public int getCode() {
		return CODE;
	}

	@Override
	public String getDescription() {
		return "Sending to network";
	}

}
