/*
  IncorrectTypeException.java / Freenet, Dijjer - A Peer to Peer HTTP Cache
  Copyright (C) 2004,2005 Change.Tv, Inc
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

package freenet.io.comm;

/**
 * Thrown if trying to set a field to a value of the wrong type
 *
 * @author ian
 */
public class IncorrectTypeException extends RuntimeException {

    public static final String VERSION = "$Id: IncorrectTypeException.java,v 1.1 2005/01/29 19:12:10 amphibian Exp $";

	private static final long serialVersionUID = 1L;

	public IncorrectTypeException(String s) {
		super(s);
	}
}