/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * Content filter for PNG's.
 * This one just verifies that a PNG is valid, and throws if it isn't.
 */
public class PNGFilter implements ContentDataFilter {

	static final byte[] pngHeader = 
		{ (byte)137, (byte)80, (byte)78, (byte)71, (byte)13, (byte)10, (byte)26, (byte)10 };
	
	public Bucket readFilter(Bucket data, BucketFactory bf, String charset,
			HashMap otherParams, FilterCallback cb) throws DataFilterException,
			IOException {
		InputStream is = data.getInputStream();
		BufferedInputStream bis = new BufferedInputStream(is);
		DataInputStream dis = new DataInputStream(bis);
		try {
			// Check the header
			byte[] headerCheck = new byte[pngHeader.length];
			dis.read(headerCheck);
			if(!Arrays.equals(headerCheck, pngHeader)) {
				// Throw an exception
				String message = l10n("invalidHeader");
				String title = l10n("invalidHeaderTitle");
				throw new DataFilterException(title, title,
						"<p>"+message+"</p>", new HTMLNode("p").addChild("#", message));
			}
		} finally {
			dis.close();
		}
		return data;
	}

	private String l10n(String key) {
		return L10n.getString("PNGFilter."+key);
	}

	public Bucket writeFilter(Bucket data, BucketFactory bf, String charset,
			HashMap otherParams, FilterCallback cb) throws DataFilterException,
			IOException {
		// TODO Auto-generated method stub
		return null;
	}

}
