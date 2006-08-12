/**
 * 
 */
package freenet.node;

import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.io.Bucket;

public class NodeARKInserter implements ClientCallback {

	/**
	 * 
	 */
	private final Node node;
	private final NodeIPDetector detector;

	/**
	 * @param node
	 */
	NodeARKInserter(Node node) {
		this.node = node;
		this.detector = node.ipDetector;
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
		Logger.minor(this, "update()");
		if(!checkIPUpdated()) return;
		Logger.minor(this, "Inserting ARK because peers list changed");
		
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
			Logger.minor(this, "Not inserting because no IP address");
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
			Logger.minor(this, "ARK inserter can't start yet");
			return;
		}
		
		Logger.minor(this, "starting inserter");
		
		SimpleFieldSet fs = this.node.exportPublicFieldSet();
		
		// Remove some unnecessary fields that only cause collisions.
		
		// Delete entire ark.* field for now. Changing this and automatically moving to the new may be supported in future.
		fs.removeSubset("ark");
		fs.removeValue("location");
		//fs.remove("version"); - keep version because of its significance in reconnection
		
		String s = fs.toString();
		
		byte[] buf;
		try {
			buf = s.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("UTF-8 not supported");
		}
		
		Bucket b = new SimpleReadOnlyArrayBucket(buf);
		
		FreenetURI uri = this.node.myARK.getInsertURI().setKeyType("USK").setSuggestedEdition(this.node.myARKNumber);
		
		Logger.minor(this, "Inserting ARK: "+uri);
		

		inserter = new ClientPutter(this, b, uri,
					new ClientMetadata("text/plain") /* it won't quite fit in an SSK anyway */, 
					this.node.makeClient((short)0).getInserterContext(true),
					this.node.chkPutScheduler, this.node.sskPutScheduler, RequestStarter.INTERACTIVE_PRIORITY_CLASS, false, false, this, null);
		
		try {
			
			inserter.start();
			
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
		} catch (InserterException e) {
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
		Logger.minor(this, "ARK insert succeeded");
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

	public void onFailure(InserterException e, BaseClientPutter state) {
		Logger.minor(this, "ARK insert failed: "+e);
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
		Logger.minor(this, "Generated URI for ARK: "+uri);
		long l = uri.getSuggestedEdition();
		if(l < this.node.myARKNumber) {
			Logger.error(this, "Inserted edition # lower than attempted: "+l+" expected "+this.node.myARKNumber);
		} else if(l > this.node.myARKNumber) {
			Logger.minor(this, "ARK number moving from "+this.node.myARKNumber+" to "+l);
			this.node.myARKNumber = l;
			this.node.writeNodeFile();
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

}