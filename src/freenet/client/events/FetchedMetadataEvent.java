package freenet.client.events;


public class FetchedMetadataEvent implements ClientEvent {

	public final static int code = 0x01;
	
	public String getDescription() {
		return "Fetched metadata";
	}

	public int getCode() {
		return code;
	}

}
