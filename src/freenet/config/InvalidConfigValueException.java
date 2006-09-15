/*
  InvalidConfigValueException.java / Freenet
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

package freenet.config;

/**
 * Thrown when the node refuses to set a config variable to a particular
 * value because it is invalid. Just because this is not thrown does not
 * necessarily mean that there are no problems with the value defined,
 * it merely means that there are no immediately detectable problems with 
 * it.
 */
public class InvalidConfigValueException extends Exception {
	private static final long serialVersionUID = -1;

	public InvalidConfigValueException(String msg) {
		super(msg);
	}

}
