package freenet.client.filter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedList;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.CountedInputStream;

public class TheoraBitstreamFilter extends OggBitstreamFilter {
	enum State {UNINITIALIZED, IDENTIFICATION_FOUND, COMMENT_FOUND, SETUP_FOUND};
	static final byte[] magicNumber = new byte[] {0x74, 0x68, 0x65, 0x6f, 0x72, 0x61};
	State currentState = State.UNINITIALIZED;

	protected TheoraBitstreamFilter(OggPage page) {
		super(page);
	}

	@Override
	boolean parse(OggPage page) throws IOException {
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		super.parse(page);
		if(!isValidStream) return false;
		LinkedList<Integer> theoraPacketLengths = new LinkedList<Integer>();
		//Assemble the Theora packets
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
					if(logMINOR) Logger.minor(this, "Header type: "+magicHeader[0]);
					if(magicHeader[0] != -128) invalidate();
					for(int i=0; i < magicNumber.length; i++) {
						if(magicHeader[i+1] != magicNumber[i]) invalidate();
					}
					//Assemble identification header
					int VMAJ = input.readUnsignedByte();
					int VMIN = input.readUnsignedByte();
					int VREV = input.readUnsignedByte();
					int FMBW = input.readShort();
					int FMBH = input.readShort();
					int PICW = input.readShort();
					PICW |= (input.readUnsignedByte() >>> 16);
					int PICH = input.readShort();
					PICH |= (input.readUnsignedByte() >>> 16);
					Logger.minor(this, "PICH now: "+PICH+" "+Integer.toBinaryString(PICH));
					int PICX = input.readUnsignedByte();
					int PICY = input.readUnsignedByte();
					long FRN = input.readInt();
					long FRD = input.readInt();
					int PARN = input.readShort() | (input.readUnsignedByte() >>> 16);
					int PARD = input.readShort() | (input.readUnsignedByte() >>> 16);
					int CS = input.readByte();
					int NOMBR = input.readShort() | (input.readUnsignedByte() >>> 16);
					short unalignedBytes = input.readShort();
					byte QUAL = (byte) (unalignedBytes &0x3f);
					byte KFGSHIFT = (byte) (unalignedBytes&0x3C0);
					byte PF = (byte) (unalignedBytes&0x1800);
					byte Res = (byte) (unalignedBytes&0xE000);

					if(VMAJ != 3) invalidate();
					if(VMIN != 2) invalidate();
					if(VREV > 1) invalidate();
					if(FMBW == 0) invalidate();
					if(FMBH == 0) invalidate();
					if(PICW > FMBW*16) invalidate();
					Logger.minor(this, "PICH: "+PICH+" FMBH: "+FMBH);
					if(PICH > FMBH*16) invalidate();
					if(PICX > FMBW*16-PICX) invalidate();
					if(PICY > FMBH*16-PICY) invalidate();
					if(FRN == 0) invalidate();
					if(FRD == 0) invalidate();
					if(!(CS == 1 || CS == 2)) invalidate();
					if(PF == 1) invalidate();
					if(Res != 0) invalidate();

					position += page.payload.length;
					theoraPacketLengths.add(position);
					currentState = State.IDENTIFICATION_FOUND;
					running = false;
					break;

				case IDENTIFICATION_FOUND:
					if(page.payload.length-position == 0) {
						running=false;
						break;
					}
					initialPosition = position;

					magicHeader = new byte[1+magicNumber.length];
					input.readFully(magicHeader);
					Logger.minor(this, "Header type: "+magicHeader[0]);
					if(magicHeader[0] != -127) invalidate();
					for(int i=0; i < magicNumber.length; i++) {
						if(magicHeader[i+1] != magicNumber[i]) invalidate();
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

					ByteArrayOutputStream data = new ByteArrayOutputStream();
					ByteArrayOutputStream finalPage = new ByteArrayOutputStream();
					DataOutputStream output = new DataOutputStream(data);
					output.write(magicHeader);
					output.writeInt(0);
					output.writeInt(0);
					finalPage.write(page.payload, 0, initialPosition);
					finalPage.write(data.toByteArray());
					finalPage.write(page.payload, (int)cin.count(), (int)(page.payload.length-cin.count()));

					pageModified = true;
					page.payload = finalPage.toByteArray();
					position += data.toByteArray().length;
					finalPage.close();
					output.close();
					theoraPacketLengths.add(position-initialPosition);
					currentState=State.COMMENT_FOUND;
				case COMMENT_FOUND:
					if(page.payload.length-position == 0) {
						running=false;
						break;
					}
					initialPosition = position;
					position += input.skipBytes(page.payload.length-position);
					theoraPacketLengths.add(position-initialPosition);
				}
			} catch(IOException e) {
				if(logMINOR) Logger.minor(this, "In theora parser caught "+e, e);
				isValidStream = false;
				throw e;
			}
		}
		if(pageModified) {
			page.recalculateSegmentLacing(theoraPacketLengths);
			page.checksum = page.calculateCRC();
		}
		return isValidStream;
	}

	private long decode32bitIntegerFrom8BitChunks(DataInputStream input) throws IOException {
		int LEN0 = input.readUnsignedByte();
		Logger.minor(this, "LEN0: "+Integer.toBinaryString(LEN0));
		int LEN1 = input.readUnsignedByte();
		Logger.minor(this, "LEN1: "+LEN1);
		int LEN2 = input.readUnsignedByte();
		Logger.minor(this, "LEN2: "+LEN2);
		int LEN3 = input.readUnsignedByte();
		Logger.minor(this, "LEN3: "+LEN3);
		int LEN = LEN0+(LEN1 >>> 8)+(LEN2 >>> 16)+(LEN3 >>> 24);
		return LEN;
	}
}
