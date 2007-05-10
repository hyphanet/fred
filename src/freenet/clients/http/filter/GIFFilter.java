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
public class GIFFilter implements ContentDataFilter {

	static final int HEADER_SIZE = 6;
	static final byte[] gif87aHeader =
		{ (byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'7', (byte)'a' };
	static final byte[] gif89aHeader =
		{ (byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'9', (byte)'a' };
		
	
	public Bucket readFilter(Bucket data, BucketFactory bf, String charset,
			HashMap otherParams, FilterCallback cb) throws DataFilterException,
			IOException {
		if(data.size() < 6) {
			throwHeaderError(l10n("tooShortTitle"), l10n("tooShort"));
		}
		InputStream is = data.getInputStream();
		BufferedInputStream bis = new BufferedInputStream(is);
		DataInputStream dis = new DataInputStream(bis);
		try {
			// Check the header
			byte[] headerCheck = new byte[HEADER_SIZE];
			dis.read(headerCheck);
			if((!Arrays.equals(headerCheck, gif87aHeader)) && (!Arrays.equals(headerCheck, gif89aHeader))) {
				throwHeaderError(l10n("invalidHeaderTitle"), l10n("invalidHeader"));
			}
		} finally {
			dis.close();
		}
		return data;
	}

	private static String l10n(String key) {
		return L10n.getString("GIFFilter."+key);
	}

	private void throwHeaderError(String shortReason, String reason) throws DataFilterException {
		// Throw an exception
		String message = l10n("notGif");
		if(reason != null) message += ' ' + reason;
		if(shortReason != null)
			message += " - (" + shortReason + ')';
		throw new DataFilterException(shortReason, shortReason,
				"<p>"+message+"</p>", new HTMLNode("p").addChild("#", message));
	}

	public Bucket writeFilter(Bucket data, BucketFactory bf, String charset,
			HashMap otherParams, FilterCallback cb) throws DataFilterException,
			IOException {
		// TODO Auto-generated method stub
		return null;
	}

}
