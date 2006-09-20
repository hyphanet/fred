package freenet.support;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/


/**
 * Thrown when trying to decode a string which is not in 
 * "<code>x-www-form-urlencoded</code>" format.
 **/

public class URLEncodedFormatException extends Exception {
	private static final long serialVersionUID = -1;
	
    URLEncodedFormatException () {}
    URLEncodedFormatException (String s) { super(s); }
}
