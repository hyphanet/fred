/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.util.Arrays;
import java.util.HashMap;

import freenet.l10n.NodeL10n;
import freenet.support.io.FileUtil;

/**
 * Content filter for M3Us
 * This one kills every comment and ensures that every file is a URL.
 * 
 * The structure of a simple M3U is just a list of valid relative
 * URLs.  The structure of an extended M3U is as follows (taken from
 * http://schworak.com/blog/e39/m3u-play-list-specification/ ):
 * 
 * #EXTM3U
 * #EXTINF:233,Title 1
 * Somewhere\title1.mp3
 * #EXTINF:129,Title 2
 * http://www.site.com/~user/title2.mp3
 * #EXTINF:-1,Stream
 * stream-2016-01-03.m3u
 * 
 * #EXTM3U starts the File
 * #EXTINF:<length in seconds>,<title>
 * <path>
 * 
 */
public class M3UFilter implements ContentDataFilter {

	static final byte[] CHAR_COMMENT_START =
		{ (byte)'#' };
	static final byte[] CHAR_NEWLINE =
		{ (byte)'\n' };
	static final byte[] CHAR_CARRIAGE_RETURN =
		{ (byte)'\r' };
    static final int MAX_URI_LENGTH = 16384;
	// static final int COMMENT_EXT_SIZE = 4;
	// static final byte[] COMMENT_EXT_START =
	// 	{ (byte)'#', (byte)'E', (byte)'X', (byte)'T' };
	// static final int EXT_HEADER_SIZE = 7;
	// static final byte[] EXT_HEADER =
    // { (byte)'#', (byte)'E', (byte)'X', (byte)'T', (byte)'M', (byte)'3', (byte)'U' };
		
	
	@Override
	public void readFilter(InputStream input, OutputStream output, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		DataInputStream dis = new DataInputStream(input);
        // TODO: Check the header whether this is an ext m3u.
        // TODO: Check the EXTINF headers instead of killing comments.
		// Check whether the line is a comment
        boolean isComment = false;
        int readcount;
        byte[] nextchar = new byte[1];
        byte[] fileUri;
        int fileIndex;
        readcount = dis.read(nextchar);
        // read each line manually
        while (readcount != -1) {
            if(Arrays.equals(nextchar, CHAR_COMMENT_START)) {
                isComment = true;
            } else {
                isComment = false;
            }
            fileIndex = 0;
            fileUri = new byte[MAX_URI_LENGTH];
            while (readcount != -1) {
                if (!isComment) {
                    // do not include carriage return in filenames
                    if (!Arrays.equals(nextchar, CHAR_CARRIAGE_RETURN)) {
                        if (fileIndex <= MAX_URI_LENGTH) {
                            fileUri[fileIndex] = nextchar[0];
                            fileIndex += readcount;
                        }
                    }
                }
                readcount = dis.read(nextchar);
                if (Arrays.equals(nextchar, CHAR_NEWLINE)) {
                    if (!isComment) {
                        // remove too long paths
                        if (fileIndex <= MAX_URI_LENGTH) {
                            // FIXME: slice the fileUri to only the part up to fileIndex (inclusive).
                            String uri = new String(fileUri, "UTF-8");
                            // FIXME: filter the URI, i.e. with processURI from GenericReadFilterCallback.
                            uri = uri;
                            output.write(uri.getBytes("UTF-8"));
                            output.write(nextchar);
                        }
                    }
                    break;
                }
            }
        }
		output.flush();
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("M3UFilter."+key);
	}

	private void throwHeaderError(String shortReason, String reason) throws DataFilterException {
		// Throw an exception
		String message = l10n("notGif");
		if(reason != null) message += ' ' + reason;
		if(shortReason != null)
			message += " - (" + shortReason + ')';
		throw new DataFilterException(shortReason, shortReason, message);
	}

	@Override
	public void writeFilter(InputStream input, OutputStream output, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		output.write(input.read());
		return;
	}

}
