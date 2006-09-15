/*
  URIPreEncoder.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
	public final static String allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-!.~'()*,;:$&+=?/@%#";

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
