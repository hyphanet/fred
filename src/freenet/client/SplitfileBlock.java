/*
  SplitfileBlock.java / Freenet
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

import freenet.support.io.Bucket;

public interface SplitfileBlock {

	/** Get block number. [0,k[ = data blocks, [k, n[ = check blocks */
	abstract int getNumber();
	
	/** Has data? */
	abstract boolean hasData();
	
	/** Get data */
	abstract Bucket getData();
	
	/** Set data */
	abstract void setData(Bucket data);


}
