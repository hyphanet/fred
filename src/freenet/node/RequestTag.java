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
	WeakReference<RequestSender> sender;
	int requestSenderFinishedCode;
	Throwable handlerThrew;
	boolean rejected;
	boolean abortedDownstreamTransfer;
	int abortedDownstreamReason;
	String abortedDownstreamDesc;
	boolean handlerDisconnected;

	public RequestTag(boolean isSSK, START start, PeerNode source) {
		super(source);
		this.start = start;
		this.isSSK = isSSK;
	}

	public void setRequestSenderFinished(int status) {
		requestSenderFinishedCode = status;
	}

	public void setSender(RequestSender rs) {
		sender = new WeakReference<RequestSender>(rs);
	}

	public void handlerThrew(Throwable t) {
		this.handlerThrew = t;
	}

	public void setServedFromDatastore() {
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
	public int expectedTransfersIn(boolean ignoreLocalVsRemote,
			int outwardTransfersPerInsert) {
		return notRoutedOnwards ? 0 : 1;
	}

	@Override
	public int expectedTransfersOut(boolean ignoreLocalVsRemote,
			int outwardTransfersPerInsert) {
		return (ignoreLocalVsRemote && source == null) ? 0 : 1;
	}

}
