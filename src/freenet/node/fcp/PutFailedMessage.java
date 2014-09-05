/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.FailureCodeTracker;
import freenet.client.InsertException;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class PutFailedMessage extends FCPMessage {

	final int code;
	final String codeDescription;
	final String extraDescription;
	final String shortCodeDescription;
	final FailureCodeTracker tracker;
	final FreenetURI expectedURI;
	final String identifier;
	final boolean global;
	final boolean isFatal;

	protected PutFailedMessage() {
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
