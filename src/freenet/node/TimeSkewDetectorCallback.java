/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/*
 * A simple interface used to tell the node that a time skew has been detected
 * and that it should complain loudly to the user about it.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public interface TimeSkewDetectorCallback {
	public void setTimeSkewDetectedUserAlert();
}
