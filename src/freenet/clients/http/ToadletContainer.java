/*
  ToadletContainer.java / Freenet
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

package freenet.clients.http;

import java.net.URI;

/** Interface for toadlet containers. Toadlets should register here. */
public interface ToadletContainer {
	
	/** Register a Toadlet. All requests whose URL starts with the given
	 * prefix will be passed to this toadlet.
	 * @param atFront If true, add to front of list (where is checked first),
	 * else add to back of list (where is checked last).
	 */
	public void register(Toadlet t, String urlPrefix, boolean atFront);

	/**
	 * Find a Toadlet by URI.
	 */
	public Toadlet findToadlet(URI uri);
	
	/**
	 * Get the name of the theme to be used by all the Toadlets
	 */
	public String getCSSName();
}
