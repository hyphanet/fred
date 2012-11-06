/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.codeshortification;

/**
 * Class for reducing the amount of code to type with regards to .equals().
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class IfNotEquals {

	/**
	 * @throws NullPointerException If value or expectedValue is null.
	 * @throws IllegalStateException If value.equals(expectedValue) == false.
	 */
	public static void thenThrow(final Object value, final Object expectedValue, String valueName) {
		if(value == null || expectedValue == null)
			throw new NullPointerException("Got " + valueName + " == " + value + " but should be " + expectedValue);
		
		if(!value.equals(expectedValue))
			throw new IllegalStateException("Got " + valueName + " == " + value + " but should be " + expectedValue);
	}
	
	/**
	 * @throws NullPointerException If value or expectedValue is null.
	 * @throws IllegalStateException If value.equals(expectedValue) == false.
	 */
	public static void thenThrow(final Object value, final Object expectedValue) {
		if(value == null || expectedValue == null)
			throw new NullPointerException("Got " + value + " but should be " + expectedValue);
		
		if(!value.equals(expectedValue))
			throw new IllegalStateException("Got " + value + " but should be " + expectedValue);
	}
	
}
