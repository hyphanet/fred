package freenet.clients.fcp;

import java.util.Date;

import freenet.client.async.ClientRequester;
import freenet.client.events.SplitfileProgressEvent;
import freenet.clients.fcp.ClientRequest.Persistence;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.support.CurrentTimeUTC;

/** The status of a request. Cached copy i.e. can be accessed outside the database thread
 * even for a persistent request.
 * 
 * Methods that change the status should be package-local, and called either
 * within freenet.clients.fcp, or via RequestStatusCache. Hence we should be 
 * able to lock the RequestStatusCache and be confident that nothing is going
 * to change under us.
 * 
 * @author toad 
 */
public abstract class RequestStatus implements Cloneable {
	
	private final String identifier;
	private boolean hasStarted;
	private boolean hasFinished;
	private boolean hasSucceeded;
	private short priority;
	private int totalBlocks;
	private int minBlocks;
	private int fetchedBlocks;
	/** @see ClientRequester#latestSuccess */
	private Date latestSuccess;
	private int fatallyFailedBlocks;
	private int failedBlocks;
	/* @see ClientRequester#latestFailure */
	private Date latestFailure;
	private boolean isTotalFinalized;
	private final Persistence persistence;
	
	/** The download or upload has finished.
	 * @param success Did it succeed? */
	synchronized void setFinished(boolean success) {
		this.latestSuccess = CurrentTimeUTC.get();
		this.hasFinished = true;
		this.hasSucceeded = success;
		this.hasStarted = true;
		this.isTotalFinalized = true;
	}
	
	synchronized void restart(boolean started) {
		// See ClientRequester.getLatestSuccess() for why this defaults to current time.
		this.latestSuccess = CurrentTimeUTC.get();
		this.hasFinished = false;
		this.hasSucceeded = false;
		this.hasStarted = started;
		this.isTotalFinalized = false;
	}
	
	/** Constructor for creating a status from a request that has already started, e.g. on
	 * startup. We will also create status when a request is created. */
	RequestStatus(String identifier, Persistence persistence, boolean started, boolean finished, 
			boolean success, int total, int min, int fetched, Date latestSuccess, int fatal,
			int failed, Date latestFailure, boolean totalFinalized, short prio) {
		this.identifier = identifier;
		this.hasStarted = started;
		this.hasFinished = finished;
		this.hasSucceeded = success;
		this.priority = prio;
		this.totalBlocks = total;
		this.minBlocks = min;
		this.fetchedBlocks = fetched;
		// clone() because Date is mutable
		this.latestSuccess
			= latestSuccess != null ? (Date)latestSuccess.clone() : null;
		this.fatallyFailedBlocks = fatal;
		this.failedBlocks = failed;
		// clone() because Date is mutable
		this.latestFailure
			= latestFailure != null ? (Date)latestFailure.clone() : null;
		this.isTotalFinalized = totalFinalized;
		this.persistence = persistence;
	}
	
	public boolean hasSucceeded() {
		return hasSucceeded;
	}

	public boolean hasFinished() {
		return hasFinished;
	}

	public short getPriority() {
		return priority;
	}

	public String getIdentifier() {
		return identifier;
	}

	public int getTotalBlocks() {
		return totalBlocks;
	}

	public boolean isTotalFinalized() {
		return isTotalFinalized;
	}

	public int getMinBlocks() {
		return minBlocks;
	}

	public int getFetchedBlocks() {
		return fetchedBlocks;
	}

	/** @deprecated Use {@link #getLastSuccess()} instead. */
	@Deprecated
	public long getLastActivity() {
		return latestSuccess != null ? latestSuccess.getTime() : 0;
	}

	public Date getLastSuccess() {
		// clone() because Date is mutable.
		return latestSuccess != null ? (Date)latestSuccess.clone() : null;
	}
	
	public Date getLastFailure() {
		// clone() because Date is mutable.
		return latestFailure != null ? (Date)latestFailure.clone() : null;
	}

	/** Get the original URI for a fetch or the final URI for an insert. */
	public abstract FreenetURI getURI();

	public abstract long getDataSize();

	public boolean isPersistentForever() {
		return persistence == Persistence.FOREVER;
	}

	public boolean isPersistent() {
		return persistence != Persistence.CONNECTION;
	}

	public int getFatalyFailedBlocks() {
		return fatallyFailedBlocks;
	}

	public int getFailedBlocks() {
		return failedBlocks;
	}

	public boolean isStarted() {
		return hasStarted;
	}

	public abstract String getFailureReason(boolean longDescription);

	public synchronized void updateStatus(SplitfileProgressEvent event) {
		this.failedBlocks = event.failedBlocks;
		this.fatallyFailedBlocks = event.fatallyFailedBlocks;
		// clone() because Date is mutable
		this.latestFailure = event.latestFailure != null ? (Date)event.latestFailure.clone() : null;
		this.fetchedBlocks = event.succeedBlocks;
		// clone() because Date is mutable
		this.latestSuccess = event.latestSuccess != null ? (Date)event.latestSuccess.clone() : null;
		this.isTotalFinalized = event.finalizedTotal;
		this.minBlocks = event.minSuccessfulBlocks;
		this.totalBlocks = event.totalBlocks;
	}
	public synchronized void setPriority(short newPriority) {
		this.priority = newPriority;
	}

	public synchronized void setStarted(boolean started) {
		this.hasStarted = started;
	}

	/** Get the preferred filename, from the URI, the filename, etc. 
	 * @return A filename or null if not enough information to give one. */
	public abstract String getPreferredFilename();

	/** Get the preferred filename, from the URI or the filename etc.
	 * @return A filename, including the localised version of "unknown" if
	 * we don't know enough.
	 */
	public String getPreferredFilenameSafe() {
		String ret = getPreferredFilename();
		if(ret == null)
			return NodeL10n.getBase().getString("RequestStatus.unknownFilename");
		else
			return ret;
	}

	public RequestStatus clone() {
		try {
			return (RequestStatus) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new Error(e);
		}
	}

}
