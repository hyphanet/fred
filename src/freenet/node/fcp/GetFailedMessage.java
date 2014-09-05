/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.FailureCodeTracker;
import freenet.client.FetchException;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;

public class GetFailedMessage extends FCPMessage {

	final int code;
	final String codeDescription;
	final String shortCodeDescription;
	final String extraDescription;
	final FailureCodeTracker tracker;
	final boolean isFatal;
	final String identifier;
	final boolean global;
	final long expectedDataLength;
	final String expectedMimeType;
	final boolean finalizedExpected;
	final FreenetURI redirectURI;
	       
        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	protected GetFailedMessage() {
	    throw new UnsupportedOperationException();
	}

	public String getShortFailedMessage() {
		return shortCodeDescription;
	}
	
	public String getLongFailedMessage() {
		if(extraDescription != null)
			return shortCodeDescription + ": " + extraDescription;
		else
			return shortCodeDescription;
	}

}
