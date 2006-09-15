/*
  Dispatcher.java / Freenet
  Created on Jan 21, 2005
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


// TODO To change the template for this generated file go to
// Window - Preferences - Java - Code Generation - Code and Comments

package freenet.io.comm;


public interface Dispatcher {

    /**
     * Handle a message.
     * @param m
     * @return false if we did not handle the message and want it to be
     * passed on to the next filter.
     */
    boolean handleMessage(Message m);

}
