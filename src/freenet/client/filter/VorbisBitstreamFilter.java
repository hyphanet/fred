/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.LinkedList;

import freenet.l10n.NodeL10n;
import freenet.support.Logger.LogLevel;
import freenet.support.io.CountedInputStream;

import freenet.support.Logger;

/**An Ogg bitstream parser for the Ogg Vorbis codec
 * @author sajack
 */
public class VorbisBitstreamFilter extends OggBitstreamFilter {
	enum State {UNINITIALIZED, IDENTIFICATION_FOUND, COMMENT_FOUND, SETUP_FOUND};
	static final byte[] magicNumber = new byte[] {0x76, 0x6f, 0x72, 0x62, 0x69, 0x73};
	State currentState = State.UNINITIALIZED;

	VorbisBitstreamFilter(OggPage page) {
		super(page);
	}

	@Override
	boolean parse(OggPage page) throws IOException {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		super.parse(page);
		if(!isValidStream) return false;
		LinkedList<Integer> vorbisPacketLengths = new LinkedList<Integer>();
		//Assemble the Vorbis packets
		boolean pageModified = false;
		CountedInputStream cin = new CountedInputStream(new ByteArrayInputStream(page.payload));
		DataInputStream input = new DataInputStream(cin);
		int position = 0;
		int initialPosition = 0;
		boolean running = true;
		byte[] magicHeader = null;
		while(running) {
			try {
				switch(currentState) {
				case UNINITIALIZED:
					//The first header must be an identification header

					/*The header packets begin with the header type and the magic number
					 * Validate both.
					 */
					magicHeader = new byte[1+magicNumber.length];
					input.readFully(magicHeader);
					if(magicHeader[0] != 1) invalidate();
					for(int i=0; i < magicNumber.length; i++) {
						if(magicHeader[i+1] != magicNumber[i]) invalidate();
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

					if(vorbis_version != 0) invalidate();
					if(audio_channels == 0) invalidate();
					if(audio_sample_rate == 0) invalidate();
					if((blocksize&0xf0 >>> 4) > (blocksize&0x0f)) invalidate();
					if(!framing_flag) invalidate();
					currentState = State.IDENTIFICATION_FOUND;
					position += cin.count();
					vorbisPacketLengths.add(position-initialPosition);
					running=false; //There must be a pagebreak here
					break;
				case IDENTIFICATION_FOUND:
					initialPosition = position;
					//We should now be dealing with a comment header. We need to remove this.
					/*The header packets begin with the header type and the magic number
					 * Validate both.
					 */
					magicHeader = new byte[1+magicNumber.length];
					input.readFully(magicHeader);
					if(magicHeader[0] != 0x3) invalidate();
					for(int i=0; i < magicNumber.length; i++) {
						if(magicHeader[i+1] != magicNumber[i]) invalidate();
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
					if(!input.readBoolean()) invalidate();
					ByteArrayOutputStream data = new ByteArrayOutputStream();
					ByteArrayOutputStream finalPage = new ByteArrayOutputStream();
					DataOutputStream output = new DataOutputStream(data);
					output.write(magicHeader);
					output.writeInt(Integer.reverseBytes(0));
					output.writeInt(0);
					output.writeBoolean(true);
					finalPage.write(page.payload, 0, position);
					finalPage.write(data.toByteArray());
					finalPage.write(page.payload, (int)cin.count(), (int)(page.payload.length-cin.count()));
					page.payload = finalPage.toByteArray();
					position += data.toByteArray().length;
					finalPage.close();
					vorbisPacketLengths.add(position-initialPosition);
					output.close();
					pageModified=true;
					currentState=State.COMMENT_FOUND;
					break;
				case COMMENT_FOUND:
					if(page.payload.length-position == 0) {
						running=false;
						break;
					}
					initialPosition = position;
					//We should now be dealing with a setup header
					position += input.skipBytes(page.payload.length-position);
					vorbisPacketLengths.add(position-initialPosition);
					currentState=State.SETUP_FOUND;
					running = false;//Pagebreak here
					break;
				case SETUP_FOUND:
					if(page.payload.length-position == 0) {
						running=false;
						break;
					}
					initialPosition = position;
					position += input.skipBytes(page.payload.length-position);
					vorbisPacketLengths.push(position-initialPosition);
					}
				if(logMINOR) Logger.minor(this, "Looping again... State: "+currentState);
			} catch(IOException e) {
				if(logMINOR) Logger.minor(this, "In vorbis parser caught "+e, e);
				isValidStream = false;
				throw e;
			}
		}

		input.close();
		if(pageModified) {
			page.recalculateSegmentLacing(vorbisPacketLengths);
			page.checksum = page.calculateCRC();
		}
		return isValidStream;
	}
}
