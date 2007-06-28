/**
 * 
 */
package freenet.node;

import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.api.Bucket;

public class NodeARKInserter implements ClientCallback {

	/**
	 * 
	 */
	private final Node node;
	private final NodeCrypto crypto;
	private final NodeIPDetector detector;
	private static boolean logMINOR;

	/**
	 * @param node
	 * @param old If true, use the old ARK rather than the new ARK
	 */
	NodeARKInserter(Node node, NodeCrypto crypto, NodeIPDetector detector) {
		this.node = node;
		this.crypto = crypto;
		this.detector = detector;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	private ClientPutter inserter;
	private boolean shouldInsert;
	private Peer[] lastInsertedPeers;
	private boolean canStart;
	
	void start() {
		canStart = true;
		update();
	}
	
	public void update() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "update()");
		if(!checkIPUpdated()) return;
		if(logMINOR) Logger.minor(this, "Inserting ARK because peers list changed");
		
		if(inserter != null) {
			// Already inserting.
			// Re-insert after finished.
			synchronized(this) {
				shouldInsert = true;
			}

			return;
		}
		// Otherwise need to start an insert
		if(node.noConnectedPeers()) {
			// Can't start an insert yet
			synchronized (this) {
				shouldInsert = true;
			}
			return;
		}	

		startInserter();
	}

	private boolean checkIPUpdated() {
		Peer[] p = detector.getPrimaryIPAddress();
		if(p == null) {
			if(logMINOR) Logger.minor(this, "Not inserting because no IP address");
			return false; // no point inserting
		}
		synchronized (this) {
			if(lastInsertedPeers != null) {
				if(p.length != lastInsertedPeers.length) return true;
				for(int i=0;i<p.length;i++)
					if(!p[i].strictEquals(lastInsertedPeers[i]))
						return true;
			} else {
				// we've not inserted an ARK that we know about (ie since startup)
				return true;
			}
		}
		return false;
	}

	private void startInserter() {
		if(!canStart) {
			if(logMINOR) Logger.minor(this, "ARK inserter can't start yet");
			return;
		}
		
		if(logMINOR) Logger.minor(this, "starting inserter");
		
		SimpleFieldSet fs = this.node.exportPublicFieldSet(true); // More or less
		
		// Remove some unnecessary fields that only cause collisions.
		
		// Delete entire ark.* field for now. Changing this and automatically moving to the new may be supported in future.
		fs.removeSubset("ark");
		fs.removeValue("location");
		fs.removeValue("sig");
		//fs.remove("version"); - keep version because of its significance in reconnection
		
		String s = fs.toString();
		
		byte[] buf;
		try {
			buf = s.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("UTF-8 not supported");
		}
		
		Bucket b = new SimpleReadOnlyArrayBucket(buf);
		
		long number = crypto.myARKNumber;
		InsertableClientSSK ark = crypto.myARK;
		FreenetURI uri = ark.getInsertURI().setKeyType("USK").setSuggestedEdition(number);
		
		if(logMINOR) Logger.minor(this, "Inserting ARK: "+uri);
		

		inserter = new ClientPutter(this, b, uri,
					new ClientMetadata("text/plain") /* it won't quite fit in an SSK anyway */, 
					node.clientCore.makeClient((short)0, true).getInsertContext(true),
					node.clientCore.requestStarters.chkPutScheduler, node.clientCore.requestStarters.sskPutScheduler, 
					RequestStarter.INTERACTIVE_PRIORITY_CLASS, false, false, this, null, null, false);
		
		try {
			
			inserter.start(false);
			
			synchronized (this) {
				if(fs.get("physical.udp") == null)
					lastInsertedPeers = null;
				else {
					try {
						String[] all = fs.getAll("physical.udp");
						Peer[] peers = new Peer[all.length];
						for(int i=0;i<all.length;i++)
							peers[i] = new Peer(all[i], false);
						lastInsertedPeers = peers;
					} catch (PeerParseException e1) {
						Logger.error(this, "Error parsing own ref: "+e1+" : "+fs.get("physical.udp"), e1);
					} catch (UnknownHostException e1) {
						Logger.error(this, "Error parsing own ref: "+e1+" : "+fs.get("physical.udp"), e1);
					}
				}
			}
		} catch (InsertException e) {
			onFailure(e, inserter);	
		}
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		// Impossible
	}

	public void onFailure(FetchException e, ClientGetter state) {
		// Impossible
	}

	public void onSuccess(BaseClientPutter state) {
		if(logMINOR) Logger.minor(this, "ARK insert succeeded");
			inserter = null;
			boolean myShouldInsert;
			synchronized (this) {
				myShouldInsert = shouldInsert;
			}
			if(myShouldInsert) {
				myShouldInsert = false;
				startInserter();
			}
			synchronized (this){
				shouldInsert = myShouldInsert;
			}
	}

	public void onFailure(InsertException e, BaseClientPutter state) {
		if(logMINOR) Logger.minor(this, "ARK insert failed: "+e);
		synchronized(this) {
			lastInsertedPeers = null;
		}
		// :(
		// Better try again
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e1) {
			// Ignore
		}
		
		startInserter();
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		if(logMINOR) Logger.minor(this, "Generated URI for ARK: "+uri);
		long l = uri.getSuggestedEdition();
		if(l < crypto.myARKNumber) {
			Logger.error(this, "Inserted edition # lower than attempted: "+l+" expected "+crypto.myARKNumber);
		} else if(l > crypto.myARKNumber) {
			if(logMINOR) Logger.minor(this, "ARK number moving from "+crypto.myARKNumber+" to "+l);
			crypto.myARKNumber = l;
			node.writeNodeFile();
		}
	}

	public void onConnectedPeer() {
		if(!checkIPUpdated()) return;
		synchronized (this) {
			if(!shouldInsert) return;
		}
		// Already inserting.
		if(inserter != null) return; 	

		synchronized (this) {
			shouldInsert = false;	
		}

		startInserter();
	}

	public void onMajorProgress() {
		// Ignore
	}

	public void onFetchable(BaseClientPutter state) {
		// Ignore, we don't care
	}

}