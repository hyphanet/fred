/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.IOException;

import freenet.l10n.NodeL10n;

/**Base class for specific logical bitstream filters. Subclasses should create
 * a method overriding <code>parse</code> which includes a call to the original
 * method.
 * @author sajack
 */
public class OggBitstreamFilter {
	long lastPageSequenceNumber;
	final int serialNumber;
	boolean isValidStream = true;

	protected OggBitstreamFilter(OggPage page) {
		serialNumber = page.getSerial();
		lastPageSequenceNumber = page.getPageNumber();
	}

	/**Does minimal validation of a page in this Ogg logical bitstream.
	 * Should be extended by subclasses.
	 * @param page An Ogg page belonging to this logical bitstream
	 * @return Whether page was properly validated
	 * @throws IOException
	 */
	boolean parse(OggPage page) throws IOException {
		if(!(page.getPageNumber() == lastPageSequenceNumber+1 || page.getPageNumber() == lastPageSequenceNumber)){
			isValidStream = false;
			throw new DataFilterException(l10n("MalformedTitle"), l10n("MalformedTitle"), l10n("MalformedMessage"));
		}
		lastPageSequenceNumber = page.getPageNumber();
		return isValidStream;
	}

	/**Constructor method generating an appropriate Ogg bitstream parser.
	 * @param page the <code>OggPage</code> from which the bitstream type will be
	 * extracted.
	 * @return a new bitstream parser or null if the <code>OggPage</code> is of an
	 * unrecognized type
	 */
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
