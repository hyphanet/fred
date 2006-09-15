/*
  ClientPutState.java / Freenet
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

package freenet.client.async;

import freenet.client.InserterException;
import freenet.support.SimpleFieldSet;

/**
 * ClientPutState
 * 
 * Represents a state in the insert process.
 */
public interface ClientPutState {

	/** Get the BaseClientPutter responsible for this request state. */
	public abstract BaseClientPutter getParent();

	/** Cancel the request. */
	public abstract void cancel();

	/** Schedule the request. */
	public abstract void schedule() throws InserterException;
	
	/**
	 * Get the token, an object which is passed around with the insert and may be
	 * used by callers.
	 */
	public Object getToken();

	/** Serialize current progress to a SimpleFieldSet.
	 * Does not have to be complete! */
	public abstract SimpleFieldSet getProgressFieldset();
}
