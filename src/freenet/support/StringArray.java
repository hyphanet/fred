/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support;

/**
 * This class implements various toString methods available in java 1.5 but not 1.4
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class StringArray {
	
	/**
	 * This method implements the equivalent of Arrays.valueOf() (java 1.5)
	 * @param array
	 * @return string
	 */
	public static String toString(Object[] array){
		if((array != null) && (array.length > 0)){
			StringBuffer sb = new StringBuffer();
			for(int i=0; i<array.length; i++)
				sb.append(array[i].toString()+'|');
			return '[' + sb.substring(0, sb.length() - 1).toString() + ']';
		}else
			return "";
	}
	
	/**
	 * This method implements the equivalent of Arrays.valueOf() (java 1.5)
	 * @param array
	 * @return string
	 */
	public static String toString(String[] array){
		if((array != null) && (array.length > 0)){
			StringBuffer sb = new StringBuffer();
			for(int i=0; i<array.length; i++)
				sb.append(array[i]+'|');
			return '[' + sb.substring(0, sb.length() - 1).toString() + ']';
		}else
			return "";
	}
	
	/**
	 * This methods returns a String[] from Object[]
	 * @param array
	 * @return string[]
	 */
	public static String[] toArray(Object[] array){
		if((array != null) && (array.length > 0)){
			String[] result = new String[array.length];
			for(int i=0; i<array.length; i++)
				result[i] = (array[i]).toString();
			return result;
		}else
			return null;
	}

	public static String[] toArray(double[] array) {
		if((array != null) && (array.length > 0)){
			String[] result = new String[array.length];
			for(int i=0; i<array.length; i++)
				result[i] = Double.toString(array[i]);
			return result;
		}else
			return null;
	}
	
	public static String toString(double[] array) {
		return toString(toArray(array));
	}
	
	public static String[] toArray(long[] array) {
		if((array != null) && (array.length > 0)){
			String[] result = new String[array.length];
			for(int i=0; i<array.length; i++)
				result[i] = Long.toString(array[i]);
			return result;
		}else
			return null;
	}
	
	public static String toString(long[] array) {
		return toString(toArray(array));
	}

	public static String[] toArray(int[] array) {
		if((array != null) && (array.length > 0)){
			String[] result = new String[array.length];
			for(int i=0; i<array.length; i++)
				result[i] = Long.toString(array[i]);
			return result;
		}else
			return null;
	}
	
	public static String toString(int[] array) {
		return toString(toArray(array));
	}
	
}
