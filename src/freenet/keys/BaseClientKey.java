/*
  BaseClientKey.java / Freenet
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
