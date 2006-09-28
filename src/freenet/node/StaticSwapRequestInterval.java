/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

public class StaticSwapRequestInterval implements SwapRequestInterval {

    int fixedInterval;
    
    public StaticSwapRequestInterval(int d) {
        fixedInterval = d;
    }
    
    public int getValue() {
        return fixedInterval;
    }

	public void set(int val) {
		this.fixedInterval = val;
	}
}
