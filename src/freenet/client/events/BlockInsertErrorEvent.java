/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import freenet.client.InsertException;
import freenet.keys.FreenetURI;

public class BlockInsertErrorEvent implements ClientEvent {

	public static final int code = 0x05;
	public final InsertException e;
	public final FreenetURI key;
	public final int retryNumber;

	public BlockInsertErrorEvent(InsertException e, FreenetURI key, int retryNumber) {
		this.e = e;
		this.key = key;
		this.retryNumber = retryNumber;
	}
	
	public String getDescription() {
		return e.getMessage()+" for "+key+" ("+retryNumber+ ')';
	}

	public int getCode() {
		return code;
	}

}
