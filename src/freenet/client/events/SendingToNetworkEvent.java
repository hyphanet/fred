package freenet.client.events;

public class SendingToNetworkEvent implements ClientEvent {
	
	final static int CODE = 0x0A;

	public int getCode() {
		return CODE;
	}

	public String getDescription() {
		return "Sending to network";
	}

}
