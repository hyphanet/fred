/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.Serializable;
import java.net.MalformedURLException;

/**
 * Anything that a Node can fetch.
 * Base class of ClientKey; non-ClientKey subclasses are things like USKs, which
 * don't directly translate to a routing key.
 * 
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * restarting downloads or losing uploads.
 */
public abstract class BaseClientKey implements Serializable {

    private static final long serialVersionUID = 1L;

    public static BaseClientKey getBaseKey(FreenetURI origURI) throws MalformedURLException {
		String keyType = origURI.getKeyType();
		if("CHK".equals(keyType))
			return new ClientCHK(origURI);
		if("SSK".equals(keyType))
			return new ClientSSK(origURI);
		if("KSK".equals(keyType))
			return ClientKSK.create(origURI.getDocName());
		if("USK".equals(keyType))
			return USK.create(origURI);
		throw new MalformedURLException("Unknown keytype from "+origURI);
	}
	
	public abstract FreenetURI getURI();
	
	protected BaseClientKey() {
	    // For serialization.
	}

}
