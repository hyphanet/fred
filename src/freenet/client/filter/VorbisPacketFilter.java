/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.support.Logger.LogLevel;
import freenet.support.Logger;

/**An Ogg bitstream parser for the Ogg Vorbis codec
 * @author sajack
 */
public class VorbisPacketFilter implements CodecPacketFilter {
	enum State {UNINITIALIZED, IDENTIFICATION_FOUND, COMMENT_FOUND, SETUP_FOUND};
	static final byte[] magicNumber = new byte[] {0x76, 0x6f, 0x72, 0x62, 0x69, 0x73};
	State currentState = State.UNINITIALIZED;

	public CodecPacket parse(CodecPacket packet) throws IOException {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		//Assemble the Vorbis packets
		DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet.payload));
		byte[] magicHeader = null;
		switch(currentState) {
		case UNINITIALIZED:
			//The first header must be an identification header

			/*The header packets begin with the header type and the magic number
			 * Validate both.
			 */
			magicHeader = new byte[1+magicNumber.length];
			input.readFully(magicHeader);
			if(magicHeader[0] != 1) return null;
			for(int i=0; i < magicNumber.length; i++) {
				if(magicHeader[i+1] != magicNumber[i]) return null;
			}
			//Assemble identification header
			long vorbis_version = Integer.reverse(input.readInt());
			int audio_channels = input.readUnsignedByte();
			long audio_sample_rate = Integer.reverseBytes(input.readInt());
			int bitrate_maximum = Integer.reverseBytes(input.readInt());
			int bitrate_nominal = Integer.reverseBytes(input.readInt());
			int bitrate_minimum = Integer.reverseBytes(input.readInt());
			int blocksize = input.readUnsignedByte();
			boolean framing_flag = input.readBoolean();

			if(vorbis_version != 0) return null;
			if(audio_channels == 0) return null;
			if(audio_sample_rate == 0) return null;
			if((blocksize & 0xf0 >>> 4) > (blocksize & 0x0f)) return null;
			if(!framing_flag) return null;
			currentState = State.IDENTIFICATION_FOUND;
			break;
		case IDENTIFICATION_FOUND:
			//We should now be dealing with a comment header. We need to remove this.
			/*The header packets begin with the header type and the magic number
			 * Validate both.
			 */
			magicHeader = new byte[1+magicNumber.length];
			input.readFully(magicHeader);
			if(magicHeader[0] != 0x3) return null;
			for(int i=0; i < magicNumber.length; i++) {
				if(magicHeader[i+1] != magicNumber[i]) return null;
			}
			long vendor_length = Integer.reverseBytes(input.readInt());
			if(logMINOR) Logger.minor(this, "Read a vendor length of "+vendor_length);
			byte[] vendor_string = new byte[(int)vendor_length];
			input.readFully(vendor_string);
			long user_comment_list_length = Integer.reverseBytes(input.readInt());
			for(long i = 0; i < user_comment_list_length; i++) {
				for(long j=Integer.reverseBytes(input.readInt()); j > 0 ; j--) {
					input.skipBytes(1);
				}
			}
			if(!input.readBoolean()) return null;
			ByteArrayOutputStream data = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(data);
			output.write(magicHeader);
			output.writeInt(0);
			output.writeInt(0);
			output.writeBoolean(true);
			output.close();
			packet = new CodecPacket(data.toByteArray());
			Logger.minor(this, "Packet size: "+packet.payload.length);
			currentState=State.COMMENT_FOUND;
			break;
		case COMMENT_FOUND:
			//We should now be dealing with a setup header
			currentState=State.SETUP_FOUND;
			break;
		case SETUP_FOUND:
			break;
		}

		input.close();

		return packet;
	}
}
