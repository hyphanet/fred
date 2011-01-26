package freenet.node;

import java.lang.ref.WeakReference;

import freenet.support.Logger;
import freenet.support.TimeUtil;

/**
 * Tag for a request.
 * 
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class RequestTag extends UIDTag {
	
	enum START {
		ASYNC_GET,
		LOCAL,
		REMOTE
	}
	
	final START start;
	final boolean isSSK;
	boolean servedFromDatastore;
	private WeakReference<RequestSender> sender;
	private boolean sent;
	private int requestSenderFinishedCode = RequestSender.NOT_FINISHED;
	Throwable handlerThrew;
	boolean rejected;
	boolean abortedDownstreamTransfer;
	int abortedDownstreamReason;
	String abortedDownstreamDesc;
	boolean handlerDisconnected;
	private WeakReference<PeerNode> waitingForOpennet;

	public RequestTag(boolean isSSK, START start, PeerNode source, boolean realTimeFlag, long uid, Node node) {
		super(source, realTimeFlag, uid, node);
		this.start = start;
		this.isSSK = isSSK;
	}

	public void setRequestSenderFinished(int status) {
		boolean noRecordUnlock;
		synchronized(this) {
			if(status == RequestSender.NOT_FINISHED) throw new IllegalArgumentException();
			requestSenderFinishedCode = status;
			if(!mustUnlock()) return;
			noRecordUnlock = this.noRecordUnlock;
		}
		innerUnlock(noRecordUnlock);
	}

	public synchronized void setSender(RequestSender rs, boolean coalesced) {
		// If it's because of transfer coalescing, we won't get anything from the RequestSender, so we should not wait for it.
		if(!coalesced)
			sent = true;
		sender = new WeakReference<RequestSender>(rs);
	}
	
	protected synchronized boolean mustUnlock() {
		if(sent && requestSenderFinishedCode == RequestSender.NOT_FINISHED) return false;
		if(waitingForOpennet != null && waitingForOpennet.get() != null) return false;
		return super.mustUnlock();
	}

	public void handlerThrew(Throwable t) {
		this.handlerThrew = t;
	}

	public synchronized void setServedFromDatastore() {
		servedFromDatastore = true;
	}

	public void setRejected() {
		rejected = true;
	}

	@Override
	public void logStillPresent(Long uid) {
		StringBuffer sb = new StringBuffer();
		sb.append("Still present after ").append(TimeUtil.formatTime(age()));
		sb.append(" : ").append(uid).append(" : start=").append(start);
		sb.append(" ssk=").append(isSSK).append(" from store=").append(servedFromDatastore);
		if(sender == null) {
			sb.append(" sender hasn't been set!");
		} else {
			RequestSender s = sender.get();
			if(s == null) {
				sb.append(" sender=null");
			} else {
				sb.append(" sender=").append(s);
				sb.append(" status=");
				sb.append(s.getStatusString());
			}
		}
		if(sent)
			sb.append(" sent");
		sb.append(" finishedCode=").append(requestSenderFinishedCode);
		sb.append(" rejected=").append(rejected);
		sb.append(" thrown=").append(handlerThrew);
		if(abortedDownstreamTransfer) {
			sb.append(" abortedDownstreamTransfer reason=");
			sb.append(abortedDownstreamReason);
			sb.append(" desc=");
			sb.append(abortedDownstreamDesc);
		}
		if(handlerDisconnected)
			sb.append(" handlerDisconnected=true");
		if(waitingForOpennet != null) {
			PeerNode pn = waitingForOpennet.get();
			sb.append(" waitingForOpennet="+pn == null ? "(null)" : pn.shortToString());
		}
		sb.append(" : ");
		sb.append(super.toString());
		if(handlerThrew != null)
			Logger.error(this, sb.toString(), handlerThrew);
		else
			Logger.error(this, sb.toString());
	}

	public void onAbortDownstreamTransfers(int reason, String desc) {
		abortedDownstreamTransfer = true;
		abortedDownstreamReason = reason;
		abortedDownstreamDesc = desc;
	}

	public void handlerDisconnected() {
		handlerDisconnected = true;
	}

	@Override
	public synchronized int expectedTransfersIn(boolean ignoreLocalVsRemote,
			int outwardTransfersPerInsert) {
		return notRoutedOnwards ? 0 : 1;
	}

	@Override
	public synchronized int expectedTransfersOut(boolean ignoreLocalVsRemote,
			int outwardTransfersPerInsert) {
		if(completedDownstreamTransfers) return 0;
		return ((!isLocal()) || ignoreLocalVsRemote) ? 1 : 0;
	}
	
	private boolean completedDownstreamTransfers;

	public synchronized void completedDownstreamTransfers() {
		this.completedDownstreamTransfers = true;
	}

	@Override
	public boolean isSSK() {
		return isSSK;
	}

	@Override
	public boolean isInsert() {
		return false;
	}

	@Override
	public boolean isOfferReply() {
		return false;
	}

	public synchronized void waitingForOpennet(PeerNode next) {
		if(waitingForOpennet != null)
			Logger.error(this, "Have already waited for opennet: "+waitingForOpennet.get()+" on "+this, new Exception("error"));
		this.waitingForOpennet = next.myRef;
	}

	public void finishedWaitingForOpennet(PeerNode next) {
		boolean noRecordUnlock;
		synchronized(this) {
			if(waitingForOpennet == null) {
				Logger.error(this, "Not waiting for opennet!");
				return;
			}
			PeerNode got = waitingForOpennet.get();
			if(got != next) {
				Logger.error(this, "Finished waiting for opennet on "+next+" but was waiting for "+got);
			}
			waitingForOpennet = null;
			if(!mustUnlock()) return;
			noRecordUnlock = this.noRecordUnlock;
		}
		innerUnlock(noRecordUnlock);
	}
	
	public synchronized boolean currentlyRoutingTo(PeerNode peer) {
		if(waitingForOpennet != null && waitingForOpennet == peer.myRef)
			return true;
		return super.currentlyRoutingTo(peer);
	}
	
}
