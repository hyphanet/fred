/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.IOException;

public abstract class OggBitstreamFilter {
	public static final byte[] VORBIS_MAGIC_NUMBER = new byte[] {0x76, 0x6f, 0x72, 0x62, 0x69, 0x73};
	int lastPageSequenceNumber;
	final int serialNumber;

	OggBitstreamFilter(OggPage page) {
		serialNumber = page.getSerial();
		lastPageSequenceNumber = page.getPageNumber();
	}

	abstract boolean isValid(OggPage page);

	abstract void parse(OggPage page) throws IOException;

	public static OggBitstreamFilter getBitstreamFilter(OggPage page) {
		for(int i = 0; i <= VORBIS_MAGIC_NUMBER.length; i++) {
			if(i == VORBIS_MAGIC_NUMBER.length) return new VorbisBitstreamFilter(page);
			if(page.getPayload()[i+1] != VORBIS_MAGIC_NUMBER[i]) break;
		}
		return null;
	}
}
