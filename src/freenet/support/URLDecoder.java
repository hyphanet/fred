package freenet.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/


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
	for (int i = 0; i < args.length; i++) {
	    System.out.println(args[i] + " -> " + decode(args[i]));
	}
    }

    /**
	 * Translates a string out of x-www-form-urlencoded format.
	 *
	 * @param s String to be translated.
	 * @return the translated String.
	 *
	 **/
	public static String decode(String s) throws URLEncodedFormatException {
		if (s.length() == 0)
			return "";
		int len = s.length();
		ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();

		for (int i = 0; i < len; i++) {
			char c = s.charAt(i);
			if (Character.isLetterOrDigit(c))
				decodedBytes.write(c);
			else if (c == '%') {
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
				} catch (NumberFormatException nfe) {
					throw new URLEncodedFormatException(s);
				}
			} else {
				try {
					byte[] encoded = (""+c).getBytes("UTF-8");
					decodedBytes.write(encoded, 0, encoded.length);
				} catch (UnsupportedEncodingException e) {
					throw new Error(e);
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
