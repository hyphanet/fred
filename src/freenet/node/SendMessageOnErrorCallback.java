/*
  SendMessageOnErrorCallback.java / Freenet
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
import freenet.io.comm.NotConnectedException;
import freenet.support.Logger;

/**
 * If the send fails, send the given message to the given node.
 * Otherwise do nothing.
 */
public class SendMessageOnErrorCallback implements AsyncMessageCallback {

    public String toString() {
        return super.toString() + ": "+msg+" "+dest;
    }
    
    Message msg;
    PeerNode dest;
    
    public SendMessageOnErrorCallback(Message message, PeerNode pn) {
        this.msg = message;
        this.dest = pn;
        if(Logger.shouldLog(Logger.MINOR, this))
        	Logger.minor(this, "Created "+this);
    }

    public void sent() {
        // Ignore
    }

    public void acknowledged() {
        // All done
    }

    public void disconnected() {
    	if(Logger.shouldLog(Logger.MINOR, this))
    		Logger.minor(this, "Disconnect trigger: "+this);
        try {
            dest.sendAsync(msg, null, 0, null);
        } catch (NotConnectedException e) {
        	if(Logger.shouldLog(Logger.MINOR, this))
        		Logger.minor(this, "Both source and destination disconnected: "+msg+" for "+this);
        }
    }

    public void fatalError() {
        disconnected();
    }
}
