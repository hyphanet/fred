/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.IOException;
import java.util.ArrayList;

import freenet.support.Logger.LogLevel;
import freenet.support.Logger;

/**An Ogg bitstream parser for the Ogg Vorbis codec
 * @author sajack
 */
public class VorbisBitstreamFilter extends OggBitstreamFilter {
	private final VorbisPacketFilter parser;

	VorbisBitstreamFilter(OggPage page) {
		super(page);
		parser = new VorbisPacketFilter();
	}

	@Override
	OggPage parse(OggPage page) throws IOException {
		page = super.parse(page);
		if(!isValidStream) return null;
		ArrayList<CodecPacket> parsedPackets = new ArrayList<CodecPacket>();
		for(CodecPacket packet : page.asPackets()) {
			packet = parser.parse(packet);
			if(packet != null) parsedPackets.add(packet);
		}
		return new OggPage(page, parsedPackets);
	}
}
