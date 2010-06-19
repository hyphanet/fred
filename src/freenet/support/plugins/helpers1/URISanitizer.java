/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.plugins.helpers1;

import java.net.MalformedURLException;
import java.util.List;

import freenet.keys.FreenetURI;
import freenet.support.Logger;

public class URISanitizer {

	public enum Options { NOMETASTRINGS, SSKFORUSK };

	public static FreenetURI sanitizeURI(String key, Options... options) throws MalformedURLException {
		return sanitizeURI(null, key, false, options);
	}

	public static FreenetURI sanitizeURI(List<String> errors, String key, boolean breakOnErrors, Options... options) throws MalformedURLException {
		if (key == null) throw new NullPointerException();
		FreenetURI uri = new FreenetURI(key);
		return sanitizeURI(errors, uri, breakOnErrors, options);
	}

	public static FreenetURI sanitizeURI(List<String> errors, FreenetURI key, boolean breakOnErrors, Options... options) throws MalformedURLException {
		if (key == null) throw new NullPointerException();

		FreenetURI tempURI = key;

Outer:	for (Options option: options) {
			switch (option) {
			case NOMETASTRINGS: {
				if (tempURI.hasMetaStrings()) {
					if (errors != null) {
						tempURI = tempURI.setMetaString(null);
						errors.add("URI did contain meta strings, removed it for you");
						if (breakOnErrors) break Outer;
					} else {
						throw new MalformedURLException("URIs with meta strings not supported");
					}
				}
				break;
			}
			case SSKFORUSK: {
				if (tempURI.isUSK()) {
					if (errors != null) {
						tempURI = tempURI.sskForUSK();
						errors.add("URI was an USK, converted it to SSK for you");
						if (breakOnErrors) break Outer;
					} else {
						throw new MalformedURLException("USK not supported, use underlying SSK instead.");
					}
				}
				break;
			}
			default : Logger.error(URISanitizer.class, "Illegal Option, how can this happen?");
			}
		}
		return tempURI;
	}
}
