/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.updater;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.node.PeerNode;

/**
 * Co-ordinates update over mandatory. Update over mandatory = updating from your peers, even
 * though they may be so much newer than you that you can't route requests through them.
 * NodeDispatcher feeds UOMAnnounce's received from peers to this class, and it decides what to
 * do about them.
 * @author toad
 */
public class UpdateOverMandatoryManager {

	final NodeUpdateManager updateManager;
	
	public UpdateOverMandatoryManager(NodeUpdateManager manager) {
		this.updateManager = manager;
	}

	/** 
	 * Handle a UOMAnnounce message. A node has sent us a message offering us use of its update
	 * over mandatory facilities in some way.
	 * @param m The message to handle.
	 * @param source The PeerNode which sent the message.
	 * @return True unless we don't want the message (in this case, always true).
	 */
	public boolean handleAnnounce(Message m, PeerNode source) {
		String jarKey = m.getString(DMT.MAIN_JAR_KEY);
		String extraJarKey = m.getString(DMT.EXTRA_JAR_KEY);
		String revocationKey = m.getString(DMT.REVOCATION_KEY);
		boolean haveRevocationKey = m.getBoolean(DMT.HAVE_REVOCATION_KEY);
		long mainJarVersion = m.getLong(DMT.MAIN_JAR_VERSION);
		long extraJarVersion = m.getLong(DMT.EXTRA_JAR_VERSION);
		long revocationKeyLastTried = m.getLong(DMT.REVOCATION_KEY_TIME_LAST_TRIED);
		int revocationKeyDNFs = m.getInt(DMT.REVOCATION_KEY_DNF_COUNT);
		long revocationKeyFileLength = m.getLong(DMT.REVOCATION_KEY_FILE_LENGTH);
		long mainJarFileLength = m.getLong(DMT.MAIN_JAR_FILE_LENGTH);
		long extraJarFileLength = m.getLong(DMT.EXTRA_JAR_FILE_LENGTH);
		int pingTime = m.getInt(DMT.PING_TIME);
		int delayTime = m.getInt(DMT.BWLIMIT_DELAY_TIME);
		System.err.println("Update Over Mandatory offer from node "+source.getPeer()+" : "+source.getName()+":");
		System.err.println("Main jar key: "+jarKey+" version="+mainJarVersion+" length="+mainJarFileLength);
		System.err.println("Extra jar key: "+extraJarKey+" version="+extraJarVersion+" length="+extraJarFileLength);
		System.err.println("Revocation key: "+revocationKey+" found="+haveRevocationKey+" length="+revocationKeyFileLength+" last had 3 DNFs "+revocationKeyLastTried+" ms ago, "+revocationKeyDNFs+" DNFs so far");
		System.err.println("Load stats: "+pingTime+"ms ping, "+delayTime+"ms bwlimit delay time");
		return true;
	}

	
	
}
