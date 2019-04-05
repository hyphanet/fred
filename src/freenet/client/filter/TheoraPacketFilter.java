package freenet.client.filter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class TheoraPacketFilter implements CodecPacketFilter {
	enum State {UNINITIALIZED, IDENTIFICATION_FOUND, COMMENT_FOUND}
	static final byte[] magicNumber = new byte[] {0x74, 0x68, 0x65, 0x6f, 0x72, 0x61};
	private State currentState = State.UNINITIALIZED;

	public CodecPacket parse(CodecPacket packet) throws IOException {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		//Assemble the Theora packets
		DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet.payload));
		byte[] magicHeader;
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
					if(magicHeader[0] != -128) throw new IOException("First header type: " + magicHeader[0]);
					checkMagicHeader(magicHeader);

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

					if(VMAJ != 3) throw new IOException("Header VMAJ: " + VMAJ);
					if(VMIN != 2) throw new IOException("Header VMIN: " + VMIN);
					if(VREV > 1) throw new IOException("Header VREV: " + VREV);
					if(FMBW == 0) throw new IOException("Header FMBW: " + FMBW);
					if(FMBH == 0) throw new IOException("Header FMBH: " + FMBH);
					if(PICW > FMBW*16) throw new IOException("Header PICW: " + PICW + "; FMBW: " + FMBW);
					if(PICH > FMBH*16) throw new IOException("Header PICH: " + PICH + "; FMBH: " + FMBH);
					if(PICX > FMBW*16-PICX)
						throw new IOException("Header PICX: " + PICX + "; FMBW: " + FMBW + "; PICX: " + PICX);
					if(PICY > FMBH*16-PICY)
						throw new IOException("Header PICY: " + PICY + "; FMBH: " + FMBH + "; PICY: " + PICY);
					if(FRN == 0) throw new IOException("Header FRN: " + FRN);
					if(FRD == 0) throw new IOException("Header FRN: " + FRN);

					/* This is a value from an enumerated list of the available color spaces, given in Table.
					 * The 'Undefined' value indicates that color space information was not available to the encoder.
					 * It MAY be specified by the application via an external means.
					 * If a 'Reserved' value is given, a decoder MAY refuse to decode the stream.
					 * Value Color Space
					 *  0     Undefined.
					 *  1     Rec. 470M.
					 *  2     Rec. 470BG.
					 *  3     Reserved.
					 * https://www.theora.org/doc/Theora.pdf CHAPTER 6. BITSTREAM HEADERS page 44 */
					if(!(CS == 0 || CS == 1 || CS == 2)) throw new IOException("Header CS: " + CS);

					if(PF == 1) throw new IOException("Header PF: " + PF);
					if(Res != 0) throw new IOException("Header Res: " + Res);

					currentState = State.IDENTIFICATION_FOUND;
					break;

				case IDENTIFICATION_FOUND:
					magicHeader = new byte[1+magicNumber.length];
					input.readFully(magicHeader);
					Logger.minor(this, "Header type: "+magicHeader[0]);
					if(magicHeader[0] != -127) throw new IOException("Header type: " + magicHeader[0]);
					checkMagicHeader(magicHeader);

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

					try (ByteArrayOutputStream data = new ByteArrayOutputStream();
						 DataOutputStream output = new DataOutputStream(data)) {
						output.write(magicHeader);
						output.writeInt(0);
						output.writeInt(0);
						packet = new CodecPacket(data.toByteArray());
					}
					currentState=State.COMMENT_FOUND;
					break;
				case COMMENT_FOUND:
					break;
				}
			} catch(IOException e) {
				if(logMINOR) Logger.minor(this, "In theora parser caught "+e, e);
				throw e;
			}

		return packet;
	}

	private long decode32bitIntegerFrom8BitChunks(DataInputStream input) throws IOException {
		int LEN0 = input.readUnsignedByte();
		int LEN1 = input.readUnsignedByte();
		int LEN2 = input.readUnsignedByte();
		int LEN3 = input.readUnsignedByte();
		return LEN0|(LEN1 << 8)|(LEN2 << 16)|(LEN3 << 24);
	}

	private void checkMagicHeader(byte[] typeAndMagicHeader) throws IOException {
		for(int i=0; i < magicNumber.length; i++) {
			if(typeAndMagicHeader[i+1] != magicNumber[i])
				throw new IOException("Packet header magicNumber[" + i + "]: " + typeAndMagicHeader[i+1]);
		}
	}
}
