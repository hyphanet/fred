/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

/**
 * Event indicating that we are attempting to compress the file.
 */
public class StartedCompressionEvent implements ClientEvent {

	public final int codec;
	
	public StartedCompressionEvent(int codec) {
		this.codec = codec;
	}
	
	static final int code = 0x08;
	
	public String getDescription() {
		return "Started compression attempt with codec "+codec;
	}

	public int getCode() {
		return code;
	}

}
