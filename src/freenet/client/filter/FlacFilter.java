package freenet.client.filter;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;

import freenet.l10n.NodeL10n;

/**Filters native FLAC data.
 * Format details may be found at <a href="http://flac.sourceforge.net/format.html"> http://flac.sourceforge.net/format.html</a>
 * @author sajack
 */
public class FlacFilter implements ContentDataFilter {
	static final byte[] magicNumber = new byte[] {0x66, 0x4C, 0x61, 0x43};
	FlacPacketFilter.State currentState = FlacPacketFilter.State.UNINITIALIZED;
	short frameHeader;


	public void readFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		FlacPacketFilter parser = new FlacPacketFilter();
		DataInputStream in = new DataInputStream(input);
		for(byte magicCharacter : magicNumber) {
			if(magicCharacter != in.readByte()) throw new DataFilterException(l10n(""), l10n(""), l10n(""));
		}
		frameHeader = (short) (in.readUnsignedShort() & 0x0000FFFF);
		FlacPacket packet = null;
		while(true) {
			try {
				packet = getPacket(in);
			} catch(EOFException e) {
				break;
			}
			if(packet instanceof FlacMetadataBlock && ((FlacMetadataBlock) packet).isLastMetadataBlock()) {
				currentState = FlacPacketFilter.State.METADATA_FOUND;
			}
			parser.parse(packet);
		}
	}

	public void writeFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		// TODO Auto-generated method stub

	}

	private FlacPacket getPacket(DataInputStream input) throws IOException {
		byte[] payload;
		switch(currentState) {
		case UNINITIALIZED:
			int header = input.readInt();
			payload = new byte[header & 0x00FFFFFF];
			input.readFully(payload);
			FlacPacket packet = new FlacMetadataBlock(header, payload);
			return packet;
		case METADATA_FOUND:
			boolean firstHalfOfSyncHeaderFound = false;
			ArrayList<Byte> buffer = new ArrayList<Byte>();
			int data = 0;
			buffer.add(new Byte((byte) ((frameHeader & 0xFF00) >>> 8)));
			buffer.add(new Byte((byte) ((frameHeader & 0x00FF) >>> 8)));
			while(true) {
				try {
					data = input.readUnsignedByte();
				} catch(EOFException e) {
					frameHeader = 0;
					payload = new byte[buffer.size()];
					for(int i = 0; !buffer.isEmpty(); i++) {
						byte item = buffer.remove(i);
						payload[i] = item;
					}
					return new FlacFrame(payload);
				}
				if(!firstHalfOfSyncHeaderFound) {
					if((data & 0xFF) == 0xFF) {
						firstHalfOfSyncHeaderFound = true;
						continue;
					}
				} else {
					if((data & 0x3F) == 0x3E) {
						frameHeader = (short) (0xFF00 | data);
						payload = new byte[buffer.size()];
						for(int i = 0; !buffer.isEmpty(); i++) {
							byte item = buffer.remove(i);
							payload[i] = item;
						}
						return new FlacFrame(payload);
					} else {
						buffer.add(new Byte((byte)0xFF));
					}
				}
				buffer.add(new Byte((byte) (data&0xFF)));
			}
		}
		return null;
	}
	private static String l10n(String key) {
		return NodeL10n.getBase().getString("FlacFilter."+key);
	}
}
