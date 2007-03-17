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
				sb.append(array.toString()+'|');
			return '[' + sb.substring(0, sb.length() - 1).toString() + ']';
		}else
			return "";
	}
}
