/*
  USKCheckerCallback.java / Freenet
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

import freenet.keys.ClientSSKBlock;

/**
 * Callback for a USKChecker
 */
interface USKCheckerCallback {

	/** Data Not Found */
	public void onDNF();
	
	/** Successfully found the latest version of the key 
	 * @param block */
	public void onSuccess(ClientSSKBlock block);
	
	/** Error committed by author */
	public void onFatalAuthorError();
	
	/** Network on our node or on nodes we have been talking to */
	public void onNetworkError();

	/** Request cancelled */
	public void onCancelled();
	
	/** Get priority to run the request at */
	public short getPriority();
	
}
