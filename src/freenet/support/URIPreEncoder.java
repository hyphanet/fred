package freenet.support;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Replace any invalid characters in a string (to be converted to a URI) with encoded chars using UTF-8.
 * 
 * This does NOT do the same thing as either java.net.URLEncoder or freenet.support.URLEncoder!
 * 
 * Its purpose is simply to allow us to accept "dirty" URIs - URIs which may contain e.g. spaces -
 * by preprocessing them before they reach the URI(String) constructor.
 * 
 * I _think_ this may be what URLEncoder is for - but it seems to have become rather confused.
 * Somebody needs to check all the calls to URLEncoder...
 */
public class URIPreEncoder {
	
	// We deliberately include '%' because we don't want to interfere with stuff which is already encoded.
	// add "#" here too, this allow anchors
	public final static String allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-!.~'()*,;:$&+=?/@%#"; 

	public static String encode(String s) {
		StringBuilder output = new StringBuilder(s.length()*2);
		for(int i=0;i<s.length();i++) {
			char c = s.charAt(i);
			if(allowedChars.indexOf(c) >= 0) {
				output.append(c);
			} else {
				String tmp = ""+c;
				try {
					for(byte u: tmp.getBytes("UTF-8")) {
						int x = u & 0xff;
						output.append('%');
						if(x < 16)
							output.append('0');
						output.append(Integer.toHexString(x));
					}
				} catch (UnsupportedEncodingException e) {
					throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
				}
			}
		}
		return output.toString();
	}
	
	/**
	 * Create a new URI from a string, which may contain characters which should have been encoded.
	 * @throws URISyntaxException If the string does not represent a valid URI, even after encoding.
	 */
	public static URI encodeURI(String s) throws URISyntaxException {
		return new URI(encode(s));
	}
}
