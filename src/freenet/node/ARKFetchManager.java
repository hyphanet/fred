package freenet.node;

import java.util.LinkedList;

import freenet.support.Logger;

/**
 * @author zothar
 * 
 * Maintains:
 * - A list of ARKFetchers who want to fetch
 */
public class ARKFetchManager {
	
	/** Our Node */
	final Node node;
	
	/** All the ARKFetchers who want to fetch */
	private final LinkedList readyARKFetchers = new LinkedList();
	
	/**
	 * Create a ARKFetchManager
	 * @param node
	 */
	public ARKFetchManager(Node node) {
		Logger.normal(this, "Creating ARKFetchManager");
		System.out.println("Creating ARKFetchManager");
		this.node = node;
	}

	public void addReadyARKFetcher(ARKFetcher arkFetcher) {
		synchronized(readyARKFetchers) {
			if(hasReadyARKFetcher(arkFetcher)) {
				Logger.error(this, arkFetcher.peer.getPeer()+" already in readyARKFetchers");
				return;
			}
			readyARKFetchers.addLast(arkFetcher);
		}
	}

	public boolean hasReadyARKFetcher(ARKFetcher arkFetcher) {
		synchronized(readyARKFetchers) {
			if(readyARKFetchers.contains(arkFetcher)) {
				return true;
			}
			return false;
		}
	}
	
	public boolean hasReadyARKFetchers() {
		synchronized (readyARKFetchers) {
			if(readyARKFetchers.size() > 0) {
				return true;
			}	
		}
		return false;
	}
	
	public void maybeStartNextReadyARKFetcher() {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		synchronized(readyARKFetchers) {
			if(node.getNumARKFetchers() >= 30) {
				if(logMINOR) Logger.minor(this, "Not starting ARKFetcher in maybeStartNextReadyARKFetcher() because there are already 30 or more ARK Fetchers running");
				return;
			}
			if(!hasReadyARKFetchers()) {
				if(logMINOR) Logger.minor(this, "maybeStartNextReadyARKFetcher() called with no ARKFetchers ready");
				return;
			}
			while( true ) {
				if(readyARKFetchers.size() <= 0) {
					break;
				}
				ARKFetcher nextARKFetcher = (ARKFetcher) readyARKFetchers.removeFirst();
				if(!nextARKFetcher.peer.isConnected()) {
					nextARKFetcher.queueRunnableImmediately();
					break;
				}
			}
		}
	}

	public void removeReadyARKFetcher(ARKFetcher arkFetcher) {
		synchronized(readyARKFetchers) {
			if(!hasReadyARKFetcher(arkFetcher)) {
				return;
			}
			readyARKFetchers.remove(arkFetcher);
		}
	}
}
