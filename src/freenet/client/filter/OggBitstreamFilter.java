/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.IOException;

public abstract class OggBitstreamFilter {
	int lastPageSequenceNumber;
	final int serialNumber;

	OggBitstreamFilter(OggPage page) {
		serialNumber = page.getSerial();
		lastPageSequenceNumber = page.getPageNumber();
	}

	abstract boolean parse(OggPage page) throws IOException;

	public static OggBitstreamFilter getBitstreamFilter(OggPage page) {
		for(int i = 0; i <= VorbisBitstreamFilter.magicNumber.length; i++) {
			if(i == VorbisBitstreamFilter.magicNumber.length) return new VorbisBitstreamFilter(page);
			if(page.getPayload()[i] != VorbisBitstreamFilter.magicNumber[i]) break;
		}
		return null;
	}
}
