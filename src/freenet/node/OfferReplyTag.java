package freenet.node;

import freenet.support.Logger;
import freenet.support.TimeUtil;

/**
 * Tag tracking an offer reply.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class OfferReplyTag extends UIDTag {

	final boolean ssk;
	
	public OfferReplyTag(boolean isSSK, PeerNode source, boolean realTimeFlag) {
		super(source, realTimeFlag);
		ssk = isSSK;
	}

	@Override
	public void logStillPresent(Long uid) {
		StringBuffer sb = new StringBuffer();
		sb.append("Still present after ").append(TimeUtil.formatTime(age()));
		sb.append(" : ssk=").append(ssk);
		Logger.error(this, sb.toString());
	}

}
