/*
  TrivialToadlet.java / Freenet
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

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.support.HTMLEncoder;

public class TrivialToadlet extends Toadlet {

	TrivialToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String fetched = uri.toString();
		String encFetched = HTMLEncoder.encode(fetched);
		String reply = "<html><head><title>You requested "+encFetched+
			"</title></head><body>You fetched <a href=\""+encFetched+"\">"+
			encFetched+"</a>.</body></html>";
		this.writeReply(ctx, 200, "text/html", "OK", reply);
	}
	
	public String supportedMethods() {
		return "GET";
	}
}
