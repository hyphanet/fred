/*
  PrivkeyHasBeenBlownException.java / Freenet
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

package freenet.node.updater;

import freenet.support.HTMLEncoder;

public class PrivkeyHasBeenBlownException extends Exception{	
	private static final long serialVersionUID = -1;
	
	PrivkeyHasBeenBlownException(String msg) {
		super("The project's private key has been blown, meaning that it has been compromized"+
			  "and shouldn't be trusted anymore. Please get a new build by hand and verify CAREFULLY"+
			  "its signature and CRC. Here is the revocation message: "+HTMLEncoder.encode(msg));
	}
}
