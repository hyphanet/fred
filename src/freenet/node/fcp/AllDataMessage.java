/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class AllDataMessage {

	final long dataLength;
	final boolean global;
	final String identifier;
	final long startupTime, completionTime;
	final String mimeType;
	
	protected AllDataMessage() {
	    throw new UnsupportedOperationException();
	}

	long dataLength() {
		return dataLength;
	}

}
