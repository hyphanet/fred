/*
  MessageItem.java / Freenet
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

package freenet.node;

import freenet.io.comm.Message;

/** A queued Message or byte[], and a callback, which may be null. */
public class MessageItem {
    
    final Message msg;
    byte[] buf;
    final AsyncMessageCallback[] cb;
    final long submitted;
    final int alreadyReportedBytes;
    /** If true, the buffer may contain several messages, and is formatted
     * for sending as a single packet.
     */
    final boolean formatted;
    final ByteCounter ctrCallback;
    
    public MessageItem(Message msg2, AsyncMessageCallback[] cb2, int alreadyReportedBytes, ByteCounter ctr) {
    	this.alreadyReportedBytes = alreadyReportedBytes;
        this.msg = msg2;
        this.cb = cb2;
        buf = null;
        formatted = false;
        this.ctrCallback = ctr;
        this.submitted = System.currentTimeMillis();
    }

    public MessageItem(byte[] data, AsyncMessageCallback[] cb2, boolean formatted, int alreadyReportedBytes, ByteCounter ctr) {
    	this.alreadyReportedBytes = alreadyReportedBytes;
        this.cb = cb2;
        this.msg = null;
        this.buf = data;
        this.formatted = formatted;
        this.ctrCallback = ctr;
        this.submitted = System.currentTimeMillis();
    }

    /**
     * Return the data contents of this MessageItem.
     */
    public byte[] getData(PeerNode pn) {
        if(buf == null)
            buf = msg.encodeToPacket(pn);
        return buf;
    }
}
