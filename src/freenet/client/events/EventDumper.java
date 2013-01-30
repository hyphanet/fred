/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import java.io.IOException;
import java.io.Writer;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

public class EventDumper implements ClientEventListener {

	final Writer w;
	final boolean removeWithProducer;
	
	public EventDumper(Writer writer, boolean removeWithProducer) {
		this.w = writer;
		this.removeWithProducer = removeWithProducer;
	}

	@Override
	public void receive(ClientEvent ce, ObjectContainer container, ClientContext context) {
		try {
			w.write(ce.getDescription()+"\n");
		} catch (IOException e) {
			// Ignore.
		}
	}

	@Override
	public void onRemoveEventProducer(ObjectContainer container) {
		if(removeWithProducer)
			container.delete(this);
	}

}
