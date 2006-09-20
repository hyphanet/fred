package freenet.client.events;

import java.io.PrintWriter;

public class EventDumper implements ClientEventListener {

	final PrintWriter pw;
	
	public EventDumper(PrintWriter writer) {
		this.pw = writer;
	}

	public void receive(ClientEvent ce) {
		pw.println(ce.getDescription());
	}

}
