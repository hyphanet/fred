/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import java.io.PrintWriter;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

public class EventDumper implements ClientEventListener {

	final PrintWriter pw;
	final boolean removeWithProducer;
	
	public EventDumper(PrintWriter writer, boolean removeWithProducer) {
		this.pw = writer;
		this.removeWithProducer = removeWithProducer;
	}

	@Override
	public void receive(ClientEvent ce, ObjectContainer container, ClientContext context) {
		pw.println(ce.getDescription());
	}

	@Override
	public void onRemoveEventProducer(ObjectContainer container) {
		if(removeWithProducer)
			container.delete(this);
	}

}
