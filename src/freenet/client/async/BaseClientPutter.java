/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

public abstract class BaseClientPutter extends ClientRequester {

	protected BaseClientPutter(short priorityClass, ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler, Object context) {
		super(priorityClass, chkScheduler, sskScheduler, context);
	}

	public abstract void onMajorProgress();

}
