/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.config;

public interface EnumerableOption {
	public String[] getPossibleValues();
	public void setPossibleValues(String[] val);
	public String getValueString();
}
