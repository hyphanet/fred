/*
  LimitedRangeIntByteArrayMapElement.java / Freenet
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

package freenet.support;

import freenet.node.AsyncMessageCallback;


public class LimitedRangeIntByteArrayMapElement {
    
    public LimitedRangeIntByteArrayMapElement(int packetNumber, byte[] data2, AsyncMessageCallback[] callbacks2) {
        this.packetNumber = packetNumber;
        this.data = data2;
        this.callbacks = callbacks2;
        createdTime = System.currentTimeMillis();
    }

    public final int packetNumber;
    public final byte[] data;
    public final AsyncMessageCallback[] callbacks;
    public final long createdTime;
    long reputTime;
    
	public void reput() {
		this.reputTime = System.currentTimeMillis();
	}
}
