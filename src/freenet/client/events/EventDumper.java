package freenet.client.events;

public class EventDumper implements ClientEventListener {

	public void receive(ClientEvent ce) {
		System.err.println(ce.getCode()+":"+ce.getDescription());
	}

}
