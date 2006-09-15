/*
  SendableRequest.java / Freenet
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

import freenet.client.async.ClientRequester;
import freenet.support.RandomGrabArrayItem;

/**
 * A low-level request which can be sent immediately. These are registered
 * on the ClientRequestScheduler.
 */
public interface SendableRequest extends RandomGrabArrayItem {
	
	public short getPriorityClass();
	
	public int getRetryCount();
	
	/** ONLY called by RequestStarter */
	public void send(NodeClientCore node);
	
	/** Get client context object */
	public Object getClient();
	
	/** Get the ClientRequest */
	public ClientRequester getClientRequest();

}
