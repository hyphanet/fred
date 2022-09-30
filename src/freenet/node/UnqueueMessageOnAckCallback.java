/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.AsyncMessageCallback;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * If the send fails, queue the given message for the given node.
 * Otherwise do nothing.
 */
public class UnqueueMessageOnAckCallback implements AsyncMessageCallback {
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

    @Override
	public String toString() {
	return super.toString() + ": " +dest + ' ' + Integer.toString(extraPeerDataFileNumber);
    }

    DarknetPeerNode dest;
    int extraPeerDataFileNumber;

    public UnqueueMessageOnAckCallback(DarknetPeerNode pn, int extraPeerDataFileNumber) {
	this.dest = pn;
	this.extraPeerDataFileNumber = extraPeerDataFileNumber;
	if(logMINOR) {
		Logger.minor(this, "Created "+this);
	}
    }

    @Override
    public void sent() {
	// Ignore
    }

    @Override
    public void acknowledged() {
	// the message was received, no need to try again.
	dest.unqueueN2NM(extraPeerDataFileNumber);
    }

    @Override
    public void disconnected() {
	// ignore
    }

    @Override
    public void fatalError() {
	// ignore
    }
}
