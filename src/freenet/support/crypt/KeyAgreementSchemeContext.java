/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.crypt;

public abstract class KeyAgreementSchemeContext {

	protected long lastUsedTime;
	protected boolean logMINOR;

	/**
	* @return The time at which this object was last used.
	*/
	public synchronized long lastUsedTime() {
		return lastUsedTime;
	}
}