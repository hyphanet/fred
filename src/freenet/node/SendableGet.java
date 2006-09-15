/*
  SendableGet.java / Freenet
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

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
public interface SendableGet extends SendableRequest {

	public ClientKey getKey();
	
	/** Called when/if the low-level request succeeds. */
	public void onSuccess(ClientKeyBlock block, boolean fromStore);
	
	/** Called when/if the low-level request fails. */
	public void onFailure(LowLevelGetException e);
	
	/** Should the request ignore the datastore? */
	public boolean ignoreStore();

	/** If true, don't cache local requests */
	public boolean dontCache();
}
