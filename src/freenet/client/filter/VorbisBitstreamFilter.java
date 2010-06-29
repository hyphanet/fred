/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.IOException;

public class VorbisBitstreamFilter extends OggBitstreamFilter {
	//enum PageType {IDENTIFICATION, COMMENT, SETUP};

	VorbisBitstreamFilter(OggPage page) {
		super(page);
	}

	@Override
	boolean isValid(OggPage page) {
		return true;
	}

	@Override
	void parse(OggPage page) throws IOException {
	}

}
