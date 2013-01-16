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

	public static String getSafeURLCharacters() {
		return safeURLCharacters;
	}
	
	public static String encode(String URL, String force, boolean ascii) {
		return encode(URL, force, ascii, "");
	}
	
	/**
	 * Encode a string for inclusion in a URI.
	 *
	 * @param  URL  String to encode
	 * @param force List of characters (in the form of a string) which must be encoded as well as the built-in.
	 * @param ascii If true, encode all foreign letters, if false, leave them as is. Set to true if you are
	 * passing to something that needs ASCII (e.g. HTTP headers), set to false if you are using in an HTML page.
	 * @return      Encoded version of string
	 */
	public static String encode(String URL, String force, boolean ascii, String extraSafeChars) {
		StringBuilder enc = new StringBuilder(URL.length());
		for (int i = 0; i < URL.length(); ++i) {
			char c = URL.charAt(i);
			if (((safeURLCharacters.indexOf(c) >= 0) || ((!ascii) && c >= 0200 && Character.isDefined(c) && !Character.isISOControl(c)) || extraSafeChars.indexOf(c) >= 0)
					&& (force == null || force.indexOf(c) < 0)) {
				enc.append(c);
				
			} else {
				try {
					for (byte b: ("" + c).getBytes("UTF-8")) {
						int x = b & 0xFF;
						if (x < 16)
							enc.append("%0");
						else
							enc.append('%');
						enc.append(Integer.toHexString(x));
					}
				} catch (UnsupportedEncodingException e) {
					throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
				}
			}
		}
		return enc.toString();
	}

	/**
	 * Encode a string for inclusion in a URI.
	 *
	 * @param  URL  String to encode
	 * @param ascii If true, encode all foreign letters, if false, leave them as is. Set to true if you are
	 * passing to something that needs ASCII (e.g. HTTP headers), set to false if you are using in an HTML page.
	 * @return      Encoded version of string
	 */
	public static String encode(String s, boolean ascii) {
		return encode(s, null, ascii);
	}

}
