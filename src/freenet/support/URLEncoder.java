/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.io.UnsupportedEncodingException;

/**
 * Encodes strings for use in URIs. Note that this is <b>NOT</b> the same as java.net.URLEncoder, which
 * encodes strings to application/x-www-urlencoded. This is much closer to what java.net.URI does. We 
 * don't turn spaces into +'s, and we allow through non-ascii characters unless told not to.
 */
public class URLEncoder {
	// Moved here from FProxy by amphibian
	final static String safeURLCharacters = "*-_./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz";

	/**
	 * Encode a string for inclusion in a URI.
	 *
	 * @param  URL  String to encode
	 * @param force List of characters (in the form of a string) which must be encoded as well as the built-in.
	 * @return      HTML-safe version of string
	 */
	public final static String encode(String URL, String force, boolean ascii) {
		StringBuffer enc = new StringBuffer(URL.length());
		for (int i = 0; i < URL.length(); ++i) {
			char c = URL.charAt(i);
			if (((safeURLCharacters.indexOf(c) >= 0) || ((!ascii) && c >= 0200))
					&& (force == null || force.indexOf(c) < 0)) {
				enc.append(c);
			} else {
				try {
					byte[] encoded = ("" + c).getBytes("UTF-8");
					for (int j = 0; j < encoded.length; j++) {
						byte b = encoded[j];
						int x = b & 0xFF;
						if (x < 16)
							enc.append("%0");
						else
							enc.append('%');
						enc.append(Integer.toHexString(x));
					}
				} catch (UnsupportedEncodingException e) {
					throw new Error(e);
				}
			}
		}
		return enc.toString();
	}

	public static String encode(String s) {
		return encode(s, null, false);
	}

}
