/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import freenet.keys.FreenetURI;

public class GeneratedURIEvent implements ClientEvent {

	public static final int code = 0x06;
	public final FreenetURI uri;

	public GeneratedURIEvent(FreenetURI uri) {
		this.uri = uri;
	}
	
	public String getDescription() {
		return "Generated URI on insert: "+uri;
	}

	public int getCode() {
		return code;
	}
}
