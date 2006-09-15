/*
  StartedCompressionEvent.java / Freenet
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

/**
 * Event indicating that we are attempting to compress the file.
 */
public class StartedCompressionEvent implements ClientEvent {

	public final int codec;
	
	public StartedCompressionEvent(int codec) {
		this.codec = codec;
	}
	
	static final int code = 0x08;
	
	public String getDescription() {
		return "Started compression attempt with codec "+codec;
	}

	public int getCode() {
		return code;
	}

}
