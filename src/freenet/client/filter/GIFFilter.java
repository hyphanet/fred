/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;

import freenet.l10n.NodeL10n;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**
 * Content filter for GIF's.
 * This one just verifies that a GIF is valid, and throws if it isn't.
 */
public class GIFFilter implements ContentDataFilter {

	static final int HEADER_SIZE = 6;
	static final byte[] gif87aHeader =
		{ (byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'7', (byte)'a' };
	static final byte[] gif89aHeader =
		{ (byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'9', (byte)'a' };
		
	
	public void readFilter(InputStream input, OutputStream output, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		DataInputStream dis = new DataInputStream(input);
		// Check the header
		byte[] headerCheck = new byte[HEADER_SIZE];
		dis.readFully(headerCheck);
		if((!Arrays.equals(headerCheck, gif87aHeader)) && (!Arrays.equals(headerCheck, gif89aHeader))) {
			throwHeaderError(l10n("invalidHeaderTitle"), l10n("invalidHeader"));
		}
		output.write(headerCheck);
		FileUtil.copy(dis, output, -1);
		output.flush();
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("GIFFilter."+key);
	}

	private void throwHeaderError(String shortReason, String reason) throws DataFilterException {
		// Throw an exception
		String message = l10n("notGif");
		if(reason != null) message += ' ' + reason;
		if(shortReason != null)
			message += " - (" + shortReason + ')';
		throw new DataFilterException(shortReason, shortReason, message);
	}

	public void writeFilter(InputStream input, OutputStream output, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		output.write(input.read());
		return;
	}

}
