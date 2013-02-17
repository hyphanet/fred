/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * Decode encoded URLs (or parts of URLs). @see URLEncoder.
 * This class does NOT decode application/x-www-form-urlencoded
 * strings, unlike @see java.net.URLDecoder. What it does is
 * decode bits of URIs, in UTF-8. This simply means that it 
 * converts encoded characters (assuming a charset of UTF-8).
 * java.net.URI does similar things internally.
 * 
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong</a>
 * Originally!
 **/
public class URLDecoder
{
    // test harness
    public static void main(String[] args) throws URLEncodedFormatException {
	for (String arg: args) {
	    System.out.println(arg + " -> " + decode(arg, false));
	}
    }

    /**
	 * Decodes a URLEncoder format string.
	 *
	 * @param s String to be translated.
	 * @param tolerant If true, be tolerant of bogus escapes; bogus escapes are treated as
	 * just plain characters. Not recommended; a hack to allow users to paste in URLs 
	 * containing %'s.
	 * @return the translated String.
	 *
	 **/
	public static String decode(String s, boolean tolerant) throws URLEncodedFormatException {
		if (s.length() == 0)
			return "";
		int len = s.length();
		ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
		boolean hasDecodedSomething = false;

		for (int i = 0; i < len; i++) {
			char c = s.charAt(i);
			if (c == '%') {
				if (i >= len - 2) {
					throw new URLEncodedFormatException(s);
				}
				char[] hexChars = new char[2];

				hexChars[0] = s.charAt(++i);
				hexChars[1] = s.charAt(++i);

				String hexval = new String(hexChars);
				try {
					long read = Fields.hexToLong(hexval);
					if (read == 0)
						throw new URLEncodedFormatException("Can't encode" + " 00");
					decodedBytes.write((int) read);
					hasDecodedSomething = true;
				} catch (NumberFormatException nfe) {
					// Not encoded?
					if(tolerant && !hasDecodedSomething) {
						try {
							byte[] buf = ('%'+hexval).getBytes("UTF-8");
							decodedBytes.write(buf, 0, buf.length);
							continue;
						} catch (UnsupportedEncodingException e) {
							throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
						}
					}
					
					throw new URLEncodedFormatException("Not a two character hex % escape: "+hexval+" in "+s);
				}
			} else {
				try {
					byte[] encoded = (""+c).getBytes("UTF-8");
					decodedBytes.write(encoded, 0, encoded.length);
				} catch (UnsupportedEncodingException e) {
					throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
				}
			}
		}
		try {
			decodedBytes.close();
			return new String(decodedBytes.toByteArray(), "utf-8");
		} catch (IOException ioe1) {
			/* if this throws something's wrong */
		}
		throw new URLEncodedFormatException(s);
	}

}
