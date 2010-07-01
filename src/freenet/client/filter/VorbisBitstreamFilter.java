/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public class VorbisBitstreamFilter extends OggBitstreamFilter {
	enum State {UNINITIALIZED, IDENTIFICATION_FOUND};
	static final byte[] magicNumber = new byte[] {0x76, 0x6f, 0x72, 0x62, 0x69, 0x73};
	State currentState = State.UNINITIALIZED;
	boolean isValidStream = true;

	VorbisBitstreamFilter(OggPage page) {
		super(page);
	}

	@Override
	boolean parse(OggPage page) throws IOException {
		if(!isValidStream) return false;
		switch(currentState) {
		case UNINITIALIZED:
			//The first page must be an identification header
			if(page.payload[0] != 1) isValidStream=false;
			for(int i=0; i<magicNumber.length;i++) {
				if(page.payload[i+1]!=magicNumber[i]){
					isValidStream=false;
					break;
				}
			}
			DataInputStream input = new DataInputStream(new ByteArrayInputStream(page.payload));
			input.skipBytes(7);
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
			input.close();
			break;
		}
		return isValidStream;
	}

}
