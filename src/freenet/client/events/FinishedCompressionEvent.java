/*
  FinishedCompressionEvent.java / Freenet
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

public class FinishedCompressionEvent implements ClientEvent {

	static final int code = 0x09;
	
	/** Codec, -1 = uncompressed */
	public final int codec;
	/** Original size */
	public final long originalSize;
	/** Compressed size */
	public final long compressedSize;

	public FinishedCompressionEvent(int codec, long origSize, long compressedSize) {
		this.codec = codec;
		this.originalSize = origSize;
		this.compressedSize = compressedSize;
	}

	public String getDescription() {
		return "Compressed data: codec="+codec+", origSize="+originalSize+", compressedSize="+compressedSize;
	}

	public int getCode() {
		return code;
	}
	
}
