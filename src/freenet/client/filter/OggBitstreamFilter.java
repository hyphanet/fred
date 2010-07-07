/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.IOException;

import freenet.l10n.NodeL10n;

public class OggBitstreamFilter {
	int lastPageSequenceNumber;
	final int serialNumber;
	boolean isValidStream = true;

	protected OggBitstreamFilter(OggPage page) {
		serialNumber = page.getSerial();
		lastPageSequenceNumber = page.getPageNumber();
	}

	boolean parse(OggPage page) throws IOException {
		if(page.getPageNumber() != lastPageSequenceNumber+1){
			isValidStream = false;
			throw new DataFilterException(l10n("MalformedTitle"), l10n("MalformedTitle"), l10n("MalformedMessage"));
		}
		lastPageSequenceNumber = page.getPageNumber();
		return isValidStream;
	}
	
	public static OggBitstreamFilter getBitstreamFilter(OggPage page) {
		for(int i = 0; i <= VorbisBitstreamFilter.magicNumber.length; i++) {
			if(i == VorbisBitstreamFilter.magicNumber.length) return new VorbisBitstreamFilter(page);
			if(page.getPayload()[i+1] != VorbisBitstreamFilter.magicNumber[i]) break;
		}
		return null;
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("OggBitstreamFilter." + key);
	}
}
