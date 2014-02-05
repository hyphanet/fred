/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

import freenet.support.compress.Compressor.CompressorType;

/**
 * Event indicating that we are attempting to compress the file.
 */
public class StartedCompressionEvent implements ClientEvent {

	public final CompressorType codec;

	public StartedCompressionEvent(CompressorType codec) {
		this.codec = codec;
	}
	
	final static int code = 0x08;
	
	@Override
	public String getDescription() {
		return "Started compression attempt with "+codec.name;
	}

	@Override
	public int getCode() {
		return code;
	}

}
