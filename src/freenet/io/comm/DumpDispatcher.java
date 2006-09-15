/*
  DumpDispatcher.java / Freenet, Dijjer - A Peer to Peer HTTP Cache
  Copyright (C) 2004,2005 Change.Tv, Inc
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

package freenet.io.comm;

/**
 * Dispatcher that just dumps everything received to stderr.
 */
public class DumpDispatcher implements Dispatcher {

    public DumpDispatcher() {
    }

    public boolean handleMessage(Message m) {
        System.err.println("Received message: "+m);
        return true;
    }
}
