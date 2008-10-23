/**
 * 
 */
package freenet.support.io;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;

class BucketChainBucketKillJob implements DBJob {

	final BucketChainBucket bcb;
	
	BucketChainBucketKillJob(BucketChainBucket bucket) {
		bcb = bucket;
	}
	
	public void run(ObjectContainer container, ClientContext context) {
		container.activate(bcb, 1);
		if(bcb.stored) return;
		System.err.println("Freeing unfinished unstored bucket "+this);
		bcb.removeFrom(container);
		container.delete(this);
	}
	
}