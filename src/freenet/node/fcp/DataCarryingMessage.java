/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.NullBucket;


public abstract class DataCarryingMessage extends BaseDataCarryingMessage {

	protected Bucket bucket;
	
}
