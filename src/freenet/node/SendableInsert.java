/*
  SendableInsert.java / Freenet
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

/**
 * Callback interface for a low level insert, which is immediately sendable. These
 * should be registered on the ClientRequestScheduler when we want to send them. It will
 * then, when it is time to send, create a thread, send the request, and call the 
 * callback below.
 */
public interface SendableInsert extends SendableRequest {

	/** Called when we successfully insert the data */
	public void onSuccess();
	
	/** Called when we don't! */
	public void onFailure(LowLevelPutException e);

}
