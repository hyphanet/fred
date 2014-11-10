/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.client.FailureCodeTracker;
import freenet.client.FetchException;
import freenet.client.FetchException.FetchExceptionMode;
import freenet.keys.FreenetURI;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

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

    public FetchException getFetchException() {
        // Data length etc have already been handled separately. Ignore them.
        if(tracker != null) {
            return new FetchException(FetchExceptionMode.getByCode(code), tracker, extraDescription);
        } else {
            return new FetchException(FetchExceptionMode.getByCode(code), extraDescription);
        }
    }

}
