/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import freenet.keys.ClientKey;

public class SimpleBlockPutEvent implements ClientEvent {

	public final static int code = 0x04;
	
	private final ClientKey key;
	
	public SimpleBlockPutEvent(ClientKey key) {
		this.key = key;
	}

	public String getDescription() {
		return "Inserting simple key: "+key.getURI();
	}

	public int getCode() {
		return code;
	}

}
