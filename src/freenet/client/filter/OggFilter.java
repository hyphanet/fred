/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;

public class OggFilter implements ContentDataFilter{
	static final byte[] magicNumber = new byte[] {0x4f, 0x67, 0x67, 0x53};
	HashMap<Integer, OggBitstreamFilter> streamFilters = new HashMap<Integer, OggBitstreamFilter>();
	public void readFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		DataInputStream in = new DataInputStream(input);
		while(true) {
			try {
				//Seek for magic number
				if(in.readByte() != magicNumber[0]) continue;
				if(in.readByte() != magicNumber[1]) continue;
				if(in.readByte() != magicNumber[2]) continue;
				if(in.readByte() != magicNumber[3]) continue;

				OggPage page = new OggPage(in);
				OggBitstreamFilter filter = null;
				if(streamFilters.containsKey(page.getSerial())) {
					filter = streamFilters.get(page.getSerial());
				} else {
					filter = OggBitstreamFilter.getBitstreamFilter(page);
					streamFilters.put(page.getSerial(), filter);
				}
				if(filter == null) continue;
				if(page.headerValid() && filter.isValid(page)) {
					output.write(magicNumber);
					filter.parse(page);
					page.write(output);
				}
			} catch(EOFException e) {
				break;
			}
		}
	}

	public void writeFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		// TODO Auto-generated method stub
		
	}

}
class OggPage {
	//Page header contained here
	final byte version;
	final byte headerType;
	final byte[] granuelPosition = new byte[8];
	final byte[] bitStreamSerial = new byte[4];
	final byte[] pageSequenceNumber = new byte[4];
	byte[] checksum = new byte[4];
	byte segments;
	byte[] segmentTable;
	byte[] payload;

	OggPage(DataInputStream input) throws IOException {
		version=input.readByte();
		headerType = input.readByte();
		input.readFully(granuelPosition);
		input.readFully(bitStreamSerial);
		input.readFully(pageSequenceNumber);
		input.readFully(checksum);
		segments = input.readByte();
		segmentTable = new byte[byteToUnsigned(segments)];
		input.read(segmentTable);
		int payloadSize = 0;
		for(int i = 0; i < byteToUnsigned(segments); i++) {
			payloadSize += byteToUnsigned(segmentTable[i]);
		}
		payload = new byte[payloadSize];
		input.read(payload);
	}

	boolean headerValid() {
		if(version != 0) return false;
		//FIXME Calculate the CRC32 here
		return true;
	}

	void write(OutputStream output) throws IOException {
		DataOutputStream out = new DataOutputStream(output);
		out.write(version);
		out.write(headerType);
		out.write(granuelPosition);
		out.write(bitStreamSerial);
		out.write(pageSequenceNumber);
		out.write(checksum);
		out.write(segments);
		out.write(segmentTable);
		out.write(payload);
	}

	int getSerial() {
		ByteBuffer bb = ByteBuffer.wrap(bitStreamSerial);
		return bb.getInt();
	}

	int getPageNumber() {
		ByteBuffer bb = ByteBuffer.wrap(pageSequenceNumber);
		return bb.getInt();
	}

	byte[] getPayload() {
		return payload;
	}

	static private int byteToUnsigned(byte input) {
		return (input & 0xff);
	}
}