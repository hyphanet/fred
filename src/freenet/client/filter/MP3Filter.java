package freenet.client.filter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

public class MP3Filter implements ContentDataFilter {

	// Various sources on the Internet.
	// The most comprehensive one appears to be:
	// http://mpgedit.org/mpgedit/mpeg_format/mpeghdr.htm
	// Others:
	// http://www.mp3-tech.org/programmer/frame_header.html
	// http://www.codeproject.com/KB/audio-video/mpegaudioinfo.aspx
	// http://www.id3.org/mp3Frame
	// http://www.mp3-converter.com/mp3codec/
	
	static final short[] [] [] bitRateIndices = {
		//Version 2.5
		{
			{},
			{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
			{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
			{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256}
		},
		//Reserved
		{
		},
		//Version 2.0
		{
			{},
			{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
			{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
			{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256}
		},
		//Version 1
		{
			{},
			{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
			{0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},
			{0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448}
		}
	};

	static final int[] [] sampleRateIndices = {
		//Version 2.5
		{11025, 12000, 8000},
		//Reserved
		{},
		//Version 2.0
		{22050, 24000, 16000},
		//Version 1
		{44100, 48000, 32000}
	};

	public void readFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		//FIXME: Add support for free formatted files(highly uncommon)
		DataInputStream in = new DataInputStream(input);
		DataOutputStream out = new DataOutputStream(output);
		boolean foundStream = false;
		int frameHeader = in.readInt();
		//Seek ahead until we find the Frame sync
		// FIXME surely the sync should be 0xffe00000 ? First 11 bits set, right?
		while( !foundStream || (frameHeader & 0xff030000) == 0xff030000) {
			if((frameHeader & 0xff030000) == 0xff030000){
				//Populate header details
				byte version = (byte) ((frameHeader & 0x00180000) >>> 19); //2 bits
				byte layer = (byte) ((frameHeader & 0x00060000) >>> 17); //2 bits
				// WARNING: layer is encoded! 1 = layer 3, 2 = layer 2, 3 = layer 1!
				boolean protectionBit = ((frameHeader & 0x00010000) >>> 16) == 1 ? true : false; //1 bit
				byte bitrateIndex = (byte) ((frameHeader & 0x0000f000) >>> 12); //4 bits
				byte samplerateIndex = (byte) ((frameHeader & 0x0000c0000) >>> 10); //2 bits
				boolean paddingBit = ((frameHeader & 0x00000300) >>> 9) == 1 ? true : false;
				boolean privateBit = ((frameHeader & 0x00000100) >>> 8) == 1 ? true : false;
				byte channelMode = (byte) ((frameHeader & 0x000000c0) >>> 6); //2 bits
				byte modeExtension = (byte) ((frameHeader & 0x00000030) >>> 4); //2 bits
				/*A small boost in security might be gained by flipping the next
				 * two bits to false. */
				boolean copyright = ((frameHeader & 0x00000008) >>> 3) == 1 ? true : false;
				boolean original = ((frameHeader & 0x00000004) >>> 2) == 1 ? true : false;
				byte emphasis = (byte) ((frameHeader & 0x00000002));

				//Generate other values from tables
				int bitrate = bitRateIndices[version][layer][bitrateIndex]*1000;
				int samplerate = sampleRateIndices[version][samplerateIndex];
				int frameLength = 0;
				if(layer == 1 || layer == 2) {
					frameLength = 144*bitrate/samplerate+(paddingBit ? 1 : 0);
				}
				else if(layer == 3) frameLength = (12*bitrate/samplerate+(paddingBit ? 1 : 0))*4;

				if(protectionBit) {
					short crc = in.readShort();
					// FIXME check the crc
					out.writeShort(crc);
				}
				
				//Write out the frame
				byte[] frame = new byte[frameLength-4];
				in.readFully(frame);
				out.writeInt(frameHeader);
				out.write(frame);
				frameHeader = in.readInt();
			} else {
				frameHeader = frameHeader << 8;
				frameHeader |= (in.readUnsignedByte());
				if((frameHeader & 0xff030000) == 0xff030000) foundStream = true;
			}

		}
		output.flush();
	}

	public void writeFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		// TODO Auto-generated method stub

	}

}
