/*
  CHKVerifyException.java / Freenet
  Copyright (C) amphibian
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

package freenet.keys;

/**
 * @author amphibian
 * 
 * Exception thrown when a CHK doesn't verify.
 */
public class CHKVerifyException extends KeyVerifyException {
	private static final long serialVersionUID = -1;

	/**
     * 
     */
    public CHKVerifyException() {
        super();
    }

    /**
     * @param message
     */
    public CHKVerifyException(String message) {
        super(message);
        // TODO Auto-generated constructor stub
    }

    /**
     * @param message
     * @param cause
     */
    public CHKVerifyException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * @param cause
     */
    public CHKVerifyException(Throwable cause) {
        super(cause);
    }

}
