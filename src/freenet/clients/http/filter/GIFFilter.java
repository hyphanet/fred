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

import freenet.support.HTMLNode;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * Content filter for PNG's.
 * This one just verifies that a PNG is valid, and throws if it isn't.
 */
public class GIFFilter implements ContentDataFilter {

	static final String ERROR_MESSAGE = 
		"The file you tried to fetch is not a GIF. "+
		"It might be some other file format, and your browser may do something dangerous with it, "+
		"therefore we have blocked it.";
	
	static final int HEADER_SIZE = 6;
	static final byte[] gif87aHeader =
		{ (byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'7', (byte)'a' };
	static final byte[] gif89aHeader =
		{ (byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'9', (byte)'a' };
		
	
	public Bucket readFilter(Bucket data, BucketFactory bf, String charset,
			HashMap otherParams, FilterCallback cb) throws DataFilterException,
			IOException {
		if(data.size() < 6) {
			throwHeaderError("Too short", "The file is too short to be a GIF.");
		}
		InputStream is = data.getInputStream();
		BufferedInputStream bis = new BufferedInputStream(is);
		DataInputStream dis = new DataInputStream(bis);
		try {
			// Check the header
			byte[] headerCheck = new byte[HEADER_SIZE];
			dis.read(headerCheck);
			if((!Arrays.equals(headerCheck, gif87aHeader)) && (!Arrays.equals(headerCheck, gif89aHeader))) {
				throwHeaderError("Invalid header", "The file does not contain a valid GIF header.");
			}
		} finally {
			dis.close();
		}
		return data;
	}

	private void throwHeaderError(String shortReason, String reason) throws DataFilterException {
		// Throw an exception
		String message = ERROR_MESSAGE;
		if(reason != null) message += ' ' + reason;
		String msg = "Not a GIF";
		if(shortReason != null)
			msg += " - " + shortReason;
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
