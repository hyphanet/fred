package freenet.client.filter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedList;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;

public class TheoraPacketFilter implements CodecPacketFilter {
	enum State {UNINITIALIZED, IDENTIFICATION_FOUND, COMMENT_FOUND};
	static final byte[] magicNumber = new byte[] {0x74, 0x68, 0x65, 0x6f, 0x72, 0x61};
	State currentState = State.UNINITIALIZED;

	public CodecPacket parse(CodecPacket packet) throws IOException {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		LinkedList<Integer> theoraPacketLengths = new LinkedList<Integer>();
		//Assemble the Theora packets
		DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet.payload));
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
					if(logMINOR) Logger.minor(this, "Header type: "+magicHeader[0]);
					if(magicHeader[0] != -128) return null;
					for(int i=0; i < magicNumber.length; i++) {
						if(magicHeader[i+1] != magicNumber[i]) return null;
					}
					//Assemble identification header
					int VMAJ = input.readUnsignedByte();
					int VMIN = input.readUnsignedByte();
					int VREV = input.readUnsignedByte();
					int FMBW = input.readShort();
					int FMBH = input.readShort();
					int PICW = input.readShort() << 8 | input.readUnsignedByte();
					int PICH = input.readShort() << 8 | input.readUnsignedByte();
					int PICX = input.readUnsignedByte();
					int PICY = input.readUnsignedByte();
					long FRN = input.readInt();
					long FRD = input.readInt();
					int PARN = input.readShort() << 8 | input.readUnsignedByte();
					int PARD = input.readShort() << 8 | input.readUnsignedByte() ;
					int CS = input.readByte();
					int NOMBR = input.readShort() << 8 | input.readUnsignedByte();
					short unalignedBytes = input.readShort();
					byte QUAL = (byte) (unalignedBytes & 0x3f);
					byte KFGSHIFT = (byte) (unalignedBytes & 0x3C0);
					byte PF = (byte) (unalignedBytes & 0x1800);
					byte Res = (byte) (unalignedBytes & 0xE000);

					if(VMAJ != 3) return null;
					if(VMIN != 2) return null;
					if(VREV > 1) return null;
					if(FMBW == 0) return null;
					if(FMBH == 0) return null;
					if(PICW > FMBW*16) return null;
					if(PICH > FMBH*16) return null;
					if(PICX > FMBW*16-PICX) return null;
					if(PICY > FMBH*16-PICY) return null;
					if(FRN == 0) return null;
					if(FRD == 0) return null;
					if(!(CS == 1 || CS == 2)) return null;
					if(PF == 1) return null;
					if(Res != 0) return null;

					currentState = State.IDENTIFICATION_FOUND;
					running = false;
					break;

				case IDENTIFICATION_FOUND:
					magicHeader = new byte[1+magicNumber.length];
					input.readFully(magicHeader);
					Logger.minor(this, "Header type: "+magicHeader[0]);
					if(magicHeader[0] != -127) return null;
					for(int i=0; i < magicNumber.length; i++) {
						if(magicHeader[i+1] != magicNumber[i]) return null;
					}

					long vendor_length = decode32bitIntegerFrom8BitChunks(input); //Represents the vendor length
					if(logMINOR) Logger.minor(this, "Vendor string is "+vendor_length+" bytes long");
					for(long i = 0; i < vendor_length; i++) {
						input.skipBytes(1);
					}
					long NCOMMENTS = decode32bitIntegerFrom8BitChunks(input); //Represents the number of comments
					for(long i = 0; i < NCOMMENTS; i++) {
						long comment_length = decode32bitIntegerFrom8BitChunks(input);
						for(long j = 0; j < comment_length; j++) {
							input.skipBytes(1);
						}
					}

					DataOutputStream output = null;
					try {
						ByteArrayOutputStream data = new ByteArrayOutputStream();
						output = new DataOutputStream(data);
						output.write(magicHeader);
						output.writeInt(0);
						output.writeInt(0);
						packet = new CodecPacket(data.toByteArray());
						output.close();
					} finally {
						Closer.close(output);
					}
					currentState=State.COMMENT_FOUND;
				case COMMENT_FOUND:
				}
			} catch(IOException e) {
				if(logMINOR) Logger.minor(this, "In theora parser caught "+e, e);
				throw e;
			}
		}

		return packet;
	}

	private long decode32bitIntegerFrom8BitChunks(DataInputStream input) throws IOException {
		int LEN0 = input.readUnsignedByte();
		int LEN1 = input.readUnsignedByte();
		int LEN2 = input.readUnsignedByte();
		int LEN3 = input.readUnsignedByte();
		int LEN = LEN0|(LEN1 << 8)|(LEN2 << 16)|(LEN3 << 24);
		return LEN;
	}
}
