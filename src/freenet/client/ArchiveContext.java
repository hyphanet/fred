/*
  ArchiveContext.java / Freenet
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

import java.util.HashSet;

import freenet.keys.FreenetURI;

/**
 * Object passed down a full fetch, including all the recursion.
 * Used, at present, for detecting archive fetch loops, hence the
 * name.
 */
public class ArchiveContext {

	HashSet soFar = new HashSet();
	final int maxArchiveLevels;
	
	public ArchiveContext(int max) {
		this.maxArchiveLevels = max;
	}
	
	/**
	 * Check for a loop.
	 * The URI provided is expected to be a reasonably unique identifier for the archive.
	 */
	public synchronized void doLoopDetection(FreenetURI key) throws ArchiveFailureException {
		if(soFar.size() > maxArchiveLevels)
			throw new ArchiveFailureException(ArchiveFailureException.TOO_MANY_LEVELS);
	}

}
