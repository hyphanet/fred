/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

/**
 * Thrown when trying to decode a string which is not in 
 * "<code>x-www-form-urlencoded</code>" format.
 **/
public class URLEncodedFormatException extends Exception {
	private static final long serialVersionUID = -1;
	
    URLEncodedFormatException () {}
    URLEncodedFormatException (String s) { super(s); }
}
