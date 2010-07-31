/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.HashMap;
import java.util.LinkedList;

/** Filters Ogg container files. These containers contain one or more
 * logical bitstreams of data encapsulated into a physical bitstream.
 * The data is broken into variable length pages, consisting of a header
 * and 0-255 segments of 0-255 bytes. For more details refer to
 * <a href="http://www.xiph.org/ogg/doc/rfc3533.txt">http://www.xiph.org/ogg/doc/rfc3533.txt</a>
 * @author sajack
 */
public class OggFilter implements ContentDataFilter{

	public void readFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		HashMap<Integer, OggBitstreamFilter> streamFilters = new HashMap<Integer, OggBitstreamFilter>();
		LinkedList<OggPage> splitPages = new LinkedList<OggPage>();
		DataInputStream in = new DataInputStream(input);
		while(true) {
			OggPage page = null;
			try {
				page = OggPage.readPage(in);
			} catch (EOFException e) {
				break;
			}
			OggBitstreamFilter filter = null;
			if(streamFilters.containsKey(page.getSerial())) {
				filter = streamFilters.get(page.getSerial());
			} else {
				filter = OggBitstreamFilter.getBitstreamFilter(page);
				streamFilters.put(page.getSerial(), filter);
			}
			if(filter == null) continue;
			if(page.headerValid() && filter.parse(page)) {
				splitPages.add(page);
				if(page.isFinalPacket()) {
					//Don't write a continuous pages unless they are all valid
					for(OggPage part : splitPages) {
						output.write(part.array());
					}
				}
			} else if(!splitPages.isEmpty()) splitPages.clear();
		}
		output.flush();
	}

	public void writeFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		// TODO Auto-generated method stub
		
	}
}