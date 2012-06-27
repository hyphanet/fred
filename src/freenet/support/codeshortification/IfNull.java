/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.codeshortification;


/**
 * Class for reducing the amount of code to type with regards to null pointers: <br>
 * <code>if(value == null) throw new NullPointerException(message);</code><br>
 * becomes:<br>
 * <code>IfNull.thenThrow(value, message);</code>
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class IfNull {
	
	public static void thenThrow(Object value) {
		if(value == null)
			throw new NullPointerException();
	}
	
	public static void thenThrow(Object value, String message) {
		if(value == null)
			throw new NullPointerException(message);
	}
	
}
