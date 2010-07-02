/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedList;

import freenet.support.io.CountedInputStream;

public class VorbisBitstreamFilter extends OggBitstreamFilter {
	enum State {UNINITIALIZED, IDENTIFICATION_FOUND, COMMENT_FOUND, SETUP_FOUND};
	static final byte[] magicNumber = new byte[] {0x76, 0x6f, 0x72, 0x62, 0x69, 0x73};
	State currentState = State.UNINITIALIZED;
	boolean isValidStream = true;
	LinkedList<Long> VorbisPacketBoundaries = new LinkedList<Long>();

	VorbisBitstreamFilter(OggPage page) {
		super(page);
	}

	@Override
	boolean parse(OggPage page) throws IOException {
		if(!isValidStream) return false;
		//Assemble the Vorbis packets
		boolean pageModified = false;
		CountedInputStream cin = new CountedInputStream(new ByteArrayInputStream(page.payload));
		DataInputStream input = new DataInputStream(cin);
		switch(currentState) {
		case UNINITIALIZED:
			//The first header must be an identification header

			/*The header packets begin with the header type and the magic number
			 * Validate both.
			 */
			byte[] magicHeader = new byte[1+magicNumber.length];
			input.readFully(magicHeader);
			if(magicHeader[0] != 1) isValidStream = false;
			for(int i=0; i < magicNumber.length; i++) {
				if(magicHeader[i+1] != magicNumber[i]) isValidStream=false;
			}
			//Assemble identification header
			int vorbis_version = input.readInt();
			byte audio_channels = input.readByte();
			int audio_sample_rate = input.readInt();
			int bitrate_maximum = input.readInt();
			int bitrate_nominal = input.readInt();
			int bitrate_minimum = input.readInt();
			byte blocksize = input.readByte();
			boolean framing_flag = input.readBoolean();

			if(vorbis_version != 0) isValidStream = false;
			if(audio_channels == 0) isValidStream = false;
			if(audio_sample_rate == 0) isValidStream = false;
			if((blocksize&0xf0 >>> 4) > (blocksize&0x0f)) isValidStream = false;
			if(!framing_flag) isValidStream = false;
			currentState = State.IDENTIFICATION_FOUND;
			VorbisPacketBoundaries.push(cin.count());
			input.close();
			break;
		case IDENTIFICATION_FOUND:
			//We should now be dealing with a comment header
			currentState=State.COMMENT_FOUND;
			break;
		case COMMENT_FOUND:
			//We should now be dealing with a setup header
			currentState=State.SETUP_FOUND;
			break;
		case SETUP_FOUND:
			break;
		}
		return isValidStream;
	}

}
