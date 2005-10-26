package freenet.client;

import java.util.HashSet;

import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;

/**
 * Object passed down a full fetch, including all the recursion.
 * Used, at present, for detecting archive fetch loops, hence the
 * name.
 */
public class ArchiveContext {

	HashSet soFar = new HashSet();
	int maxArchiveLevels;
	
	public synchronized void doLoopDetection(ClientKey key) throws ArchiveFailureException {
		if(!soFar.add(key))
			throw new ArchiveFailureException("Archive loop detected");
		if(soFar.size() > maxArchiveLevels)
			throw new ArchiveFailureException(ArchiveFailureException.TOO_MANY_LEVELS);
	}

}
