/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.support.Logger;
import freenet.support.LogThresholdCallback;

/**
 * If the send fails, send the given message to the given node.
 * Otherwise do nothing.
 */
public class SendMessageOnErrorCallback implements AsyncMessageCallback {
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}

	@Override
	public String toString() {
		return super.toString() + ": "+msg+ ' ' +dest;
	}

	Message msg;
	PeerNode dest;
	ByteCounter ctr;

	public SendMessageOnErrorCallback(Message message, PeerNode pn, ByteCounter ctr) {
		this.msg = message;
		this.dest = pn;
		this.ctr = ctr;
		if(logMINOR) {
			Logger.minor(this, "Created "+this);
		}
	}

	public void sent() {
		// Ignore
	}

	public void acknowledged() {
		// All done
	}

	public void disconnected() {
		if(logMINOR)
			Logger.minor(this, "Disconnect trigger: "+this);
		try {
			dest.sendAsync(msg, null, ctr);
		} catch (NotConnectedException e) {
			if(logMINOR) {
				Logger.minor(this, "Both source and destination disconnected: "+msg+" for "+this);
			}
		}
	}

	public void fatalError() {
		disconnected();
	}
}
