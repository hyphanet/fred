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
	public final static String allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-!.~'()*,;:$&+=?/@%";

	public static String encode(String s) {
		StringBuffer output = new StringBuffer(s.length()*2);
		for(int i=0;i<s.length();i++) {
			char c = s.charAt(i);
			if(allowedChars.indexOf(c) >= 0) {
				output.append(c);
			} else {
				String tmp = ""+c;
				try {
					byte[] utf = tmp.getBytes("UTF-8");
					for(int j=0;j<utf.length;j++) {
						int x = utf[j] & 0xff;
						output.append('%');
						if(x < 16)
							output.append('0');
						output.append(Integer.toHexString(x));
					}
				} catch (UnsupportedEncodingException e) {
					throw new Error(e);
				}
			}
		}
		return output.toString();
	}
	
	public static URI encodeURI(String s) throws URISyntaxException {
		return new URI(encode(s));
	}
}
