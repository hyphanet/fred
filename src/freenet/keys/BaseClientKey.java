/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.net.MalformedURLException;

/**
 * Anything that a Node can fetch.
 * Base class of ClientKey; non-ClientKey subclasses are things like USKs, which
 * don't directly translate to a routing key.
 */
public abstract class BaseClientKey {

	public static BaseClientKey getBaseKey(FreenetURI origURI) throws MalformedURLException {
		if("CHK".equals(origURI.getKeyType()))
			return new ClientCHK(origURI);
		if("SSK".equals(origURI.getKeyType()))
			return new ClientSSK(origURI);
		if("KSK".equals(origURI.getKeyType()))
			return ClientKSK.create(origURI.getDocName());
		if("USK".equals(origURI.getKeyType()))
			return USK.create(origURI);
		throw new UnsupportedOperationException("Unknown keytype from "+origURI);
	}
	
	public abstract FreenetURI getURI();

}
