/*
  StartableSplitfileBlock.java / Freenet
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

package freenet.client;

import freenet.keys.FreenetURI;

/** Simple interface for a splitfile block */
public interface StartableSplitfileBlock extends SplitfileBlock {

	/** Start the fetch (or insert). Implementation is required to call relevant
	 * methods on RetryTracker when done. */
	abstract void start();

	/**
	 * Shut down the fetch as soon as reasonably possible.
	 */
	abstract public void kill();

	abstract public int getRetryCount();
	
	/**
	 * Get the URI of the file. For an insert, this is derived during insert.
	 * For a request, it is fixed in the constructor.
	 */
	abstract public FreenetURI getURI();

}
