/*
  HttpPlugin.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  Copyright (C) 2006 David 'Bombe' Roden <bombe@freenetproject.org>
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

package freenet.plugin;

import java.io.IOException;

import freenet.clients.http.HTTPRequest;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;

/**
 * Interface for plugins that support HTTP interaction.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public interface HttpPlugin extends Plugin {

	/**
	 * Handles the GET request.
	 * 
	 * @param request
	 *            The request used to interact with this plugin
	 * @param context
	 *            The context of the HTTP request
	 * @throws IOException
	 *             if an I/O error occurs
	 * @throws ToadletContextClosedException
	 *             if the context has already been closed.
	 */
	public void handleGet(HTTPRequest request, ToadletContext context) throws IOException, ToadletContextClosedException;

	/**
	 * Handles the POST request.
	 * 
	 * @param request
	 *            The request used to interact with this plugin
	 * @param context
	 *            The context of the HTTP request
	 * @throws IOException
	 *             if an I/O error occurs
	 * @throws ToadletContextClosedException
	 *             if the context has already been closed.
	 */
	public void handlePost(HTTPRequest request, ToadletContext context) throws IOException, ToadletContextClosedException;

}
