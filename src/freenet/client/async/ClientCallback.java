/*
  ClientCallback.java / Freenet
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

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InserterException;
import freenet.keys.FreenetURI;

/**
 * A client process. Something that initiates requests, and can cancel
 * them. FCP, FProxy, and the GlobalPersistentClient, implement this
 * somewhere.
 */
public interface ClientCallback {

	public void onSuccess(FetchResult result, ClientGetter state);
	
	public void onFailure(FetchException e, ClientGetter state);

	public void onSuccess(BaseClientPutter state);
	
	public void onFailure(InserterException e, BaseClientPutter state);
	
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state);
	
	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public void onMajorProgress();

	/** Called when the inserted data is fetchable (don't rely on this) */
	public void onFetchable(BaseClientPutter state);
}
