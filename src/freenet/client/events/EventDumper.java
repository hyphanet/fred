/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import java.io.PrintWriter;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

public class EventDumper implements ClientEventListener {

	final PrintWriter pw;
	
	public EventDumper(PrintWriter writer) {
		this.pw = writer;
	}

	public void receive(ClientEvent ce, ObjectContainer container, ClientContext context) {
		pw.println(ce.getDescription());
	}

}
