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
		if(origURI.getKeyType().equals("CHK"))
			return new ClientCHK(origURI);
		if(origURI.getKeyType().equals("SSK"))
			return new ClientSSK(origURI);
		if(origURI.getKeyType().equals("KSK"))
			return ClientKSK.create(origURI.getDocName());
		if(origURI.getKeyType().equals("USK"))
			return USK.create(origURI);
		throw new UnsupportedOperationException("Unknown keytype from "+origURI);
	}
	
	public abstract FreenetURI getURI();

}
