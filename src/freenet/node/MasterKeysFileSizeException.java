/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

public class MasterKeysFileSizeException extends Exception {

	final private static long serialVersionUID = -2753942792186990130L;

	final public boolean tooBig;

	public MasterKeysFileSizeException(boolean tooBig) {
		this.tooBig = tooBig;
	}

	public boolean isTooBig() {
		return tooBig;
	}

	public String sizeToString() {
		return tooBig? "big" : "small";
	}

}
