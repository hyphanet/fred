/*
  BlockInsertErrorEvent.java / Freenet
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

package freenet.client.events;

import freenet.client.InserterException;
import freenet.keys.FreenetURI;

public class BlockInsertErrorEvent implements ClientEvent {

	public static final int code = 0x05;
	public final InserterException e;
	public final FreenetURI key;
	public final int retryNumber;

	public BlockInsertErrorEvent(InserterException e, FreenetURI key, int retryNumber) {
		this.e = e;
		this.key = key;
		this.retryNumber = retryNumber;
	}
	
	public String getDescription() {
		return e.getMessage()+" for "+key+" ("+retryNumber+")";
	}

	public int getCode() {
		return code;
	}

}
