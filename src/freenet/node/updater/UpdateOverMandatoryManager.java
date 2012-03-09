/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.updater;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.BinaryBlob;
import freenet.client.async.BinaryBlobFormatException;
import freenet.client.async.BinaryBlobWriter;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutCallback;
import freenet.client.async.ClientPutter;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.async.SimpleBlockSet;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.BulkReceiver;
import freenet.io.xfer.BulkTransmitter;
import freenet.io.xfer.PartiallyReceivedBulk;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeStarter;
import freenet.node.PeerNode;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.node.Version;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.FileBucket;
import freenet.support.io.RandomAccessFileWrapper;
import freenet.support.io.RandomAccessThing;

/**
 * Co-ordinates update over mandatory. Update over mandatory = updating from your peers, even
 * though they may be so much newer than you that you can't route requests through them.
 * NodeDispatcher feeds UOMAnnounce's received from peers to this class, and it decides what to
 * do about them.
 * @author toad
 */
public class UpdateOverMandatoryManager implements RequestClient {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	final NodeUpdateManager updateManager;
	/** Set of PeerNode's which say (or said before they disconnected) 
	 * the key has been revoked */
	private final HashSet<PeerNode> nodesSayKeyRevoked;
	/** Set of PeerNode's which say the key has been revoked but failed
	 * to transfer the revocation key. */
	private final HashSet<PeerNode> nodesSayKeyRevokedFailedTransfer;
	/** Set of PeerNode's which say the key has been revoked and are transferring the revocation certificate. */
	private final HashSet<PeerNode> nodesSayKeyRevokedTransferring;
	/** PeerNode's which have offered the main jar which we are not fetching it from right now */
	private final HashSet<PeerNode> nodesOfferedMainJar;
	/** PeerNode's which have offered the ext jar which we are not fetching it from right now */
	private final HashSet<PeerNode> nodesOfferedExtJar;
	/** PeerNode's we've asked to send the main jar */
	private final HashSet<PeerNode> nodesAskedSendMainJar;
	/** PeerNode's we've asked to send the ext jar */
	private final HashSet<PeerNode> nodesAskedSendExtJar;
	/** PeerNode's sending us the main jar */
	private final HashSet<PeerNode> nodesSendingMainJar;
	/** PeerNode's sending us the ext jar */
	private final HashSet<PeerNode> nodesSendingExtJar;
	// 2 for reliability, no more as gets very slow/wasteful
	static final int MAX_NODES_SENDING_JAR = 2;
	/** Maximum time between asking for the main jar and it starting to transfer */
	static final int REQUEST_MAIN_JAR_TIMEOUT = 60 * 1000;
	//** Grace time before we use UoM to update */
	public static final int GRACE_TIME = 3 * 60 * 60 * 1000; // 3h
	private UserAlert alert;
	private static final Pattern extBuildNumberPattern = Pattern.compile("^ext(?:-jar)?-(\\d+)\\.fblob$");
	private static final Pattern mainBuildNumberPattern = Pattern.compile("^main(?:-jar)?-(\\d+)\\.fblob$");
	private static final Pattern extTempBuildNumberPattern = Pattern.compile("^ext(?:-jar)?-(\\d+-)?(\\d+)\\.fblob\\.tmp*$");
	private static final Pattern mainTempBuildNumberPattern = Pattern.compile("^main(?:-jar)?-(\\d+-)?(\\d+)\\.fblob\\.tmp*$");
	private static final Pattern revocationTempBuildNumberPattern = Pattern.compile("^revocation(?:-jar)?-(\\d+-)?(\\d+)\\.fblob\\.tmp*$");

	public UpdateOverMandatoryManager(NodeUpdateManager manager) {
		this.updateManager = manager;
		nodesSayKeyRevoked = new HashSet<PeerNode>();
		nodesSayKeyRevokedFailedTransfer = new HashSet<PeerNode>();
		nodesSayKeyRevokedTransferring = new HashSet<PeerNode>();
		nodesOfferedMainJar = new HashSet<PeerNode>();
		nodesOfferedExtJar = new HashSet<PeerNode>();
		nodesAskedSendMainJar = new HashSet<PeerNode>();
		nodesAskedSendExtJar = new HashSet<PeerNode>();
		nodesSendingMainJar = new HashSet<PeerNode>();
		nodesSendingExtJar = new HashSet<PeerNode>();
	}

	/** 
	 * Handle a UOMAnnounce message. A node has sent us a message offering us use of its update
	 * over mandatory facilities in some way.
	 * @param m The message to handle.
	 * @param source The PeerNode which sent the message.
	 * @return True unless we don't want the message (in this case, always true).
	 */
	public boolean handleAnnounce(Message m, final PeerNode source) {
		String mainJarKey = m.getString(DMT.MAIN_JAR_KEY);
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

		// Log it

		if(logMINOR) {
			Logger.minor(this, "Update Over Mandatory offer from node " + source.getPeer() + " : " + source.userToString() + ":");
			Logger.minor(this, "Main jar key: " + mainJarKey + " version=" + mainJarVersion + " length=" + mainJarFileLength);
			Logger.minor(this, "Extra jar key: " + extraJarKey + " version=" + extraJarVersion + " length=" + extraJarFileLength);
			Logger.minor(this, "Revocation key: " + revocationKey + " found=" + haveRevocationKey + " length=" + revocationKeyFileLength + " last had 3 DNFs " + revocationKeyLastTried + " ms ago, " + revocationKeyDNFs + " DNFs so far");
			Logger.minor(this, "Load stats: " + pingTime + "ms ping, " + delayTime + "ms bwlimit delay time");
		}

		// Now the core logic

		// First off, if a node says it has the revocation key, and its key is the same as ours,
		// we should 1) suspend any auto-updates and tell the user, 2) try to download it, and 
		// 3) if the download fails, move the notification; if the download succeeds, process it

		if(haveRevocationKey) {

			if(updateManager.isBlown())
				return true; // We already know

			// First, is the key the same as ours?
			try {
				FreenetURI revocationURI = new FreenetURI(revocationKey);
				if(revocationURI.equals(updateManager.revocationURI)) {

					// Uh oh...

					// Have to do this first to avoid race condition
					synchronized(this) {
						// If already transferring, don't start another transfer.
						if(nodesSayKeyRevokedTransferring.contains(source)) return true;
						// If waiting for SendingRevocation, don't start another transfer.
						if(nodesSayKeyRevoked.contains(source)) return true;
						nodesSayKeyRevoked.add(source);
					}

					// Disable the update
					updateManager.peerClaimsKeyBlown();

					// Tell the user
					alertUser();

					System.err.println("Your peer " + source.userToString() +
							" (build #" + source.getSimpleVersion() + ") says that the auto-update key is blown!");
					System.err.println("Attempting to fetch it...");

					tryFetchRevocation(source);
					
				} else {
					// Should probably also be a useralert?
					Logger.normal(this, "Node " + source + " sent us a UOM claiming that the auto-update key was blown, but it used a different key to us: \nour key=" + updateManager.revocationURI + "\nhis key=" + revocationURI);
				}
			} catch(MalformedURLException e) {
				// Should maybe be a useralert?
				Logger.error(this, "Node " + source + " sent us a UOMAnnounce claiming that the auto-update key was blown, but it had an invalid revocation URI: " + revocationKey + " : " + e, e);
				System.err.println("Node " + source.userToString() + " sent us a UOMAnnounce claiming that the revocation key was blown, but it had an invalid revocation URI: " + revocationKey + " : " + e);
			} catch(NotConnectedException e) {
				System.err.println("Node " + source + " says that the auto-update key was blown, but has now gone offline! Something bad may be happening!");
				Logger.error(this, "Node " + source + " says that the auto-update key was blown, but has now gone offline! Something bad may be happening!");
				synchronized(UpdateOverMandatoryManager.this) {
					nodesSayKeyRevoked.remove(source);
					// Might be valid, but no way to tell except if other peers tell us.
					// And there's a good chance it isn't.
				}
				maybeNotRevoked();
			}

		}

		if(updateManager.isBlown())
			return true; // We already know

		if(!updateManager.isEnabled())
			return true; // Don't care if not enabled, except for the revocation URI

		long now = System.currentTimeMillis();
		
		handleMainJarOffer(now, mainJarFileLength, mainJarVersion, source, mainJarKey);
		
		handleExtJarOffer(now, extraJarFileLength, extraJarVersion, source, extraJarKey);
		
		return true;
	}

	private void tryFetchRevocation(final PeerNode source) throws NotConnectedException {
		// Try to transfer it.

		Message msg = DMT.createUOMRequestRevocation(updateManager.node.random.nextLong());
		source.sendAsync(msg, new AsyncMessageCallback() {

			@Override
			public void acknowledged() {
				// Ok
			}

			@Override
			public void disconnected() {
				// :(
				System.err.println("Failed to send request for revocation key to " + source.userToString() + 
						" (build #" + source.getSimpleVersion() + ") because it disconnected!");
				source.failedRevocationTransfer();
				synchronized(UpdateOverMandatoryManager.this) {
					nodesSayKeyRevokedFailedTransfer.add(source);
				}
			}

			@Override
			public void fatalError() {
				// Not good!
				System.err.println("Failed to send request for revocation key to " + source.userToString() + " because of a fatal error.");
			}

			@Override
			public void sent() {
				// Cool
			}
		}, updateManager.ctr);
		
		updateManager.node.getTicker().queueTimedJob(new Runnable() {

			@Override
			public void run() {
				if(updateManager.isBlown()) return;
				synchronized(UpdateOverMandatoryManager.this) {
					if(nodesSayKeyRevokedFailedTransfer.contains(source)) return;
					if(nodesSayKeyRevokedTransferring.contains(source)) return;
					nodesSayKeyRevoked.remove(source);
				}
				System.err.println("Peer "+source+" (build #" + source.getSimpleVersion() + ") said that the auto-update key had been blown, but did not transfer the revocation certificate. The most likely explanation is that the key has not been blown (the node is buggy or malicious), so we are ignoring this.");
				maybeNotRevoked();
			}
			
		}, 60*1000);

	// The reply message will start the transfer. It includes the revocation URI
	// so we can tell if anything wierd is happening.

	}

	private void handleMainJarOffer(long now, long mainJarFileLength, long mainJarVersion, PeerNode source, String jarKey) {
		
		long started = updateManager.getStartedFetchingNextMainJarTimestamp();
		long whenToTakeOverTheNormalUpdater;
		if(started > 0)
			whenToTakeOverTheNormalUpdater = started + GRACE_TIME;
		else
			whenToTakeOverTheNormalUpdater = System.currentTimeMillis() + GRACE_TIME;
		boolean isOutdated = updateManager.node.isOudated();
		// if the new build is self-mandatory or if the "normal" updater has been trying to update for more than one hour
		Logger.normal(this, "We received a valid UOMAnnounce (main) : (isOutdated=" + isOutdated + " version=" + mainJarVersion + " whenToTakeOverTheNormalUpdater=" + TimeUtil.formatTime(whenToTakeOverTheNormalUpdater - now) + ") file length " + mainJarFileLength + " updateManager version " + updateManager.newMainJarVersion());
		if(mainJarVersion > Version.buildNumber() && mainJarFileLength > 0 &&
			mainJarVersion > updateManager.newMainJarVersion()) {
			source.setMainJarOfferedVersion(mainJarVersion);
			// Offer is valid.
			if(logMINOR)
				Logger.minor(this, "Offer is valid");
			if((isOutdated) || whenToTakeOverTheNormalUpdater < now) {
				// Take up the offer, subject to limits on number of simultaneous downloads.
				// If we have fetches running already, then sendUOMRequestMain() will add the offer to nodesOfferedMainJar,
				// so that if all our fetches fail, we can fetch from this node.
				if(!isOutdated) {
					String howLong = TimeUtil.formatTime(now - started);
					Logger.error(this, "The update process seems to have been stuck for " + howLong + "; let's switch to UoM! SHOULD NOT HAPPEN! (1)");
					System.out.println("The update process seems to have been stuck for " + howLong + "; let's switch to UoM! SHOULD NOT HAPPEN! (1)");
				} else if(logMINOR)
					Logger.minor(this, "Fetching via UOM as our build is deprecated");
				// Fetch it
				try {
					FreenetURI mainJarURI = new FreenetURI(jarKey).setSuggestedEdition(mainJarVersion);
					if(mainJarURI.equals(updateManager.updateURI.setSuggestedEdition(mainJarVersion)))
						sendUOMRequest(source, true, false);
					else
						System.err.println("Node " + source.userToString() + " offered us a new main jar (version " + mainJarVersion + ") but his key was different to ours:\n" +
							"our key: " + updateManager.updateURI + "\nhis key:" + mainJarURI);
				} catch(MalformedURLException e) {
					// Should maybe be a useralert?
					Logger.error(this, "Node " + source + " sent us a UOMAnnounce claiming to have a new ext jar, but it had an invalid URI: " + jarKey + " : " + e, e);
					System.err.println("Node " + source.userToString() + " sent us a UOMAnnounce claiming to have a new ext jar, but it had an invalid URI: " + jarKey + " : " + e);
				}
			} else {
				// Don't take up the offer. Add to nodesOfferedMainJar, so that we know where to fetch it from when we need it.
				synchronized(this) {
					nodesOfferedMainJar.add(source);
				}
				updateManager.node.getTicker().queueTimedJob(new Runnable() {

					@Override
					public void run() {
						if(updateManager.isBlown())
							return;
						if(!updateManager.isEnabled())
							return;
						if(updateManager.hasNewMainJar())
							return;
						if(!updateManager.node.isOudated()) {
							Logger.error(this, "The update process seems to have been stuck for too long; let's switch to UoM! SHOULD NOT HAPPEN! (2) (ext)");
							System.out.println("The update process seems to have been stuck for too long; let's switch to UoM! SHOULD NOT HAPPEN! (2) (ext)");
						}
						maybeRequestMainJar();
					}
				}, whenToTakeOverTheNormalUpdater - now);
			}
		}
		
	}

	private void handleExtJarOffer(long now, long extJarFileLength, long extJarVersion, PeerNode source, String jarKey) {
		
		long started = updateManager.getStartedFetchingNextExtJarTimestamp();
		long whenToTakeOverTheNormalUpdater;
		if(started > 0)
			whenToTakeOverTheNormalUpdater = started + GRACE_TIME;
		else
			whenToTakeOverTheNormalUpdater = System.currentTimeMillis() + GRACE_TIME;
		boolean isOutdated = updateManager.node.isOudated();
		// if the new build is self-mandatory or if the "normal" updater has been trying to update for more than one hour
		Logger.normal(this, "We received a valid UOMAnnounce (ext) : (isOutdated=" + isOutdated + " version=" + extJarVersion + " whenToTakeOverTheNormalUpdater=" + TimeUtil.formatTime(whenToTakeOverTheNormalUpdater - now) + ") file length " + extJarFileLength + " updateManager version " + updateManager.newExtJarVersion());
		if(extJarVersion > NodeStarter.extBuildNumber && extJarFileLength > 0 &&
			extJarVersion > updateManager.newExtJarVersion()) {
			source.setExtJarOfferedVersion(extJarVersion);
			// Offer is valid.
			if(logMINOR)
				Logger.minor(this, "Offer is valid");
			if((isOutdated) || whenToTakeOverTheNormalUpdater < now) {
				// Take up the offer, subject to limits on number of simultaneous downloads.
				// If we have fetches running already, then sendUOMRequestMain() will add the offer to nodesOfferedMainJar,
				// so that if all our fetches fail, we can fetch from this node.
				if(!isOutdated) {
					String howLong = TimeUtil.formatTime(now - started);
					Logger.error(this, "The update process seems to have been stuck for " + howLong + "; let's switch to UoM! SHOULD NOT HAPPEN! (1) (ext)");
					System.out.println("The update process seems to have been stuck for " + howLong + "; let's switch to UoM! SHOULD NOT HAPPEN! (1) (ext)");
				} else if(logMINOR)
					Logger.minor(this, "Fetching via UOM as our build is deprecated");
				// Fetch it
				try {
					FreenetURI extJarURI = new FreenetURI(jarKey).setSuggestedEdition(extJarVersion);
					if(extJarURI.equals(updateManager.extURI.setSuggestedEdition(extJarVersion)))
						sendUOMRequest(source, true, true);
					else
						System.err.println("Node " + source.userToString() + " offered us a new ext jar (version " + extJarVersion + ") but his key was different to ours:\n" +
							"our key: " + updateManager.extURI + "\nhis key:" + extJarURI);
				} catch(MalformedURLException e) {
					// Should maybe be a useralert?
					Logger.error(this, "Node " + source + " sent us a UOMAnnounce claiming to have a new ext jar, but it had an invalid URI: " + jarKey + " : " + e, e);
					System.err.println("Node " + source.userToString() + " sent us a UOMAnnounce claiming to have a new jar, but it had an invalid URI: " + jarKey + " : " + e);
				}
			} else {
				// Don't take up the offer. Add to nodesOfferedMainJar, so that we know where to fetch it from when we need it.
				synchronized(this) {
					nodesOfferedExtJar.add(source);
				}
				updateManager.node.getTicker().queueTimedJob(new Runnable() {

					@Override
					public void run() {
						if(updateManager.isBlown())
							return;
						if(!updateManager.isEnabled())
							return;
						if(updateManager.hasNewExtJar())
							return;
						if(!updateManager.node.isOudated()) {
							Logger.error(this, "The update process seems to have been stuck for too long; let's switch to UoM! SHOULD NOT HAPPEN! (2)");
							System.out.println("The update process seems to have been stuck for too long; let's switch to UoM! SHOULD NOT HAPPEN! (2)");
						}
						maybeRequestExtJar();
					}
				}, whenToTakeOverTheNormalUpdater - now);
			}
		}
		
	}

	private void sendUOMRequest(final PeerNode source, boolean addOnFail, final boolean isExt) {
		final String name = isExt ? "Extra" : "Main";
		String lname = isExt ? "ext" : "main";
		if(logMINOR)
			Logger.minor(this, "sendUOMRequest"+name+"(" + source + "," + addOnFail + ")");
		if(!source.isConnected() || source.isSeed()) {
                    if(logMINOR) Logger.minor(this, "Not sending UOM "+lname+" request to "+source+" (disconnected or seednode)");
                    return;
                }
		final HashSet<PeerNode> sendingJar = isExt ? nodesSendingExtJar : nodesSendingMainJar;
		final HashSet<PeerNode> askedSendJar = isExt ? nodesAskedSendExtJar : nodesAskedSendMainJar;
		synchronized(this) {
			long offeredVersion = isExt ? source.getExtJarOfferedVersion() : source.getMainJarOfferedVersion();
			long updateVersion = isExt ? updateManager.newExtJarVersion() : updateManager.newMainJarVersion();
			if(offeredVersion < updateVersion) {
				if(offeredVersion <= 0)
					Logger.error(this, "Not sending UOM "+lname+" request to " + source + " because it hasn't offered anything!");
				else
					if(logMINOR)
						Logger.minor(this, "Not sending UOM "+lname+" request to " + source + " because we already have its offered version " + offeredVersion);
				return;
			}
			int curVersion = isExt ? updateManager.getExtVersion() : updateManager.getMainVersion();
			if(curVersion >= offeredVersion) {
				if(logMINOR)
					Logger.minor(this, "Not fetching from " + source + " because current "+lname+" jar version " + curVersion + " is more recent than " + offeredVersion);
				return;
			}
			if(askedSendJar.contains(source)) {
				if(logMINOR)
					Logger.minor(this, "Recently asked node " + source + " ("+lname+") so not re-asking yet.");
				return;
			}
			if(addOnFail && askedSendJar.size() + sendingJar.size() >= MAX_NODES_SENDING_JAR) {
				HashSet<PeerNode> offeredJar = isExt ? nodesOfferedExtJar : nodesOfferedMainJar;
				if(offeredJar.add(source))
					System.err.println("Offered "+lname+" jar by " + source.userToString() + " (already fetching from " + sendingJar.size() + "), but will use this offer if our current fetches fail).");
				return;
			} else {
				if(sendingJar.contains(source)) {
					if(logMINOR)
						Logger.minor(this, "Not fetching "+lname+" jar from " + source.userToString() + " because already fetching from that node");
					return;
				}
				sendingJar.add(source);
			}
		}

		Message msg = isExt ? DMT.createUOMRequestExtra(updateManager.node.random.nextLong()) :
			DMT.createUOMRequestMain(updateManager.node.random.nextLong());

		try {
			System.err.println("Fetching "+lname+" jar from " + source.userToString());
			source.sendAsync(msg, new AsyncMessageCallback() {

				@Override
				public void acknowledged() {
					// Cool! Wait for the actual transfer.
				}

				@Override
				public void disconnected() {
					Logger.normal(this, "Disconnected from " + source.userToString() + " after sending UOMRequest"+name);
					synchronized(UpdateOverMandatoryManager.this) {
						sendingJar.remove(source);
					}
					if(isExt)
						maybeRequestExtJar();
					else
						maybeRequestMainJar();
				}

				@Override
				public void fatalError() {
					Logger.normal(this, "Fatal error from " + source.userToString() + " after sending UOMRequest"+name);
					synchronized(UpdateOverMandatoryManager.this) {
						askedSendJar.remove(source);
					}
					if(isExt)
						maybeRequestExtJar();
					else
						maybeRequestMainJar();
				}

				@Override
				public void sent() {
					// Timeout...
					updateManager.node.ticker.queueTimedJob(new Runnable() {

						@Override
						public void run() {
							synchronized(UpdateOverMandatoryManager.this) {
								if(!askedSendJar.contains(source))
									return;
								askedSendJar.remove(source); // free up a slot
							}
							if(isExt)
								maybeRequestExtJar();
							else
								maybeRequestMainJar();
						}
					}, REQUEST_MAIN_JAR_TIMEOUT);
				}
			}, updateManager.ctr);
		} catch(NotConnectedException e) {
			synchronized(this) {
				askedSendJar.remove(source);
			}
			if(isExt)
				maybeRequestExtJar();
			else
				maybeRequestMainJar();
		}
	}

	protected void maybeRequestMainJar() {
		PeerNode[] offers;
		synchronized(this) {
			if(nodesAskedSendMainJar.size() + nodesSendingMainJar.size() >= MAX_NODES_SENDING_JAR)
				return;
			if(nodesOfferedMainJar.isEmpty())
				return;
			offers = nodesOfferedMainJar.toArray(new PeerNode[nodesOfferedMainJar.size()]);
		}
		for(int i = 0; i < offers.length; i++) {
			if(!offers[i].isConnected())
				continue;
			synchronized(this) {
				if(nodesAskedSendMainJar.size() + nodesSendingMainJar.size() >= MAX_NODES_SENDING_JAR)
					return;
				if(nodesSendingMainJar.contains(offers[i]))
					continue;
				if(nodesAskedSendMainJar.contains(offers[i]))
					continue;
			}
			sendUOMRequest(offers[i], false, false);
		}
	}

	protected void maybeRequestExtJar() {
		PeerNode[] offers;
		synchronized(this) {
			if(nodesAskedSendExtJar.size() + nodesSendingExtJar.size() >= MAX_NODES_SENDING_JAR)
				return;
			if(nodesOfferedExtJar.isEmpty())
				return;
			offers = nodesOfferedExtJar.toArray(new PeerNode[nodesOfferedExtJar.size()]);
		}
		for(int i = 0; i < offers.length; i++) {
			if(!offers[i].isConnected())
				continue;
			synchronized(this) {
				if(nodesAskedSendExtJar.size() + nodesSendingExtJar.size() >= MAX_NODES_SENDING_JAR)
					return;
				if(nodesSendingExtJar.contains(offers[i]))
					continue;
				if(nodesAskedSendExtJar.contains(offers[i]))
					continue;
			}
			sendUOMRequest(offers[i], false, true);
		}
	}

	private void alertUser() {
		synchronized(this) {
			if(alert != null)
				return;
			alert = new PeersSayKeyBlownAlert();
		}
		updateManager.node.clientCore.alerts.register(alert);
	}

	private class PeersSayKeyBlownAlert extends AbstractUserAlert {

		public PeersSayKeyBlownAlert() {
			super(false, null, null, null, null, UserAlert.WARNING, true, null, false, null);
		}

		@Override
		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");

			div.addChild("p").addChild("#", l10n("intro"));

			PeerNode[][] nodes = getNodesSayBlown();
			PeerNode[] nodesSayBlownConnected = nodes[0];
			PeerNode[] nodesSayBlownDisconnected = nodes[1];
			PeerNode[] nodesSayBlownFailedTransfer = nodes[2];

			if(nodesSayBlownConnected.length > 0)
				div.addChild("p").addChild("#", l10n("fetching"));
			else
				div.addChild("p").addChild("#", l10n("failedFetch"));

			if(nodesSayBlownConnected.length > 0) {
				div.addChild("p").addChild("#", l10n("connectedSayBlownLabel"));
				HTMLNode list = div.addChild("ul");
				for(int i = 0; i < nodesSayBlownConnected.length; i++) {
					list.addChild("li", nodesSayBlownConnected[i].userToString() + " (" + nodesSayBlownConnected[i].getPeer() + ")");
				}
			}

			if(nodesSayBlownDisconnected.length > 0) {
				div.addChild("p").addChild("#", l10n("disconnectedSayBlownLabel"));
				HTMLNode list = div.addChild("ul");
				for(int i = 0; i < nodesSayBlownDisconnected.length; i++) {
					list.addChild("li", nodesSayBlownDisconnected[i].userToString() + " (" + nodesSayBlownDisconnected[i].getPeer() + ")");
				}
			}

			if(nodesSayBlownFailedTransfer.length > 0) {
				div.addChild("p").addChild("#", l10n("failedTransferSayBlownLabel"));
				HTMLNode list = div.addChild("ul");
				for(int i = 0; i < nodesSayBlownFailedTransfer.length; i++) {
					list.addChild("li", nodesSayBlownFailedTransfer[i].userToString() + " (" + nodesSayBlownFailedTransfer[i].getPeer() + ")");
				}
			}

			return div;
		}

		private String l10n(String key) {
			return NodeL10n.getBase().getString("PeersSayKeyBlownAlert." + key);
		}

		private String l10n(String key, String pattern, String value) {
			return NodeL10n.getBase().getString("PeersSayKeyBlownAlert." + key, pattern, value);
		}

		@Override
		public String getText() {
			StringBuilder sb = new StringBuilder();
			sb.append(l10n("intro")).append("\n\n");
			PeerNode[][] nodes = getNodesSayBlown();
			PeerNode[] nodesSayBlownConnected = nodes[0];
			PeerNode[] nodesSayBlownDisconnected = nodes[1];
			PeerNode[] nodesSayBlownFailedTransfer = nodes[2];

			if(nodesSayBlownConnected.length > 0)
				sb.append(l10n("fetching")).append("\n\n");
			else
				sb.append(l10n("failedFetch")).append("\n\n");

			if(nodesSayBlownConnected.length > 0) {
				sb.append(l10n("connectedSayBlownLabel")).append("\n\n");
				for(int i = 0; i < nodesSayBlownConnected.length; i++) {
					sb.append(nodesSayBlownConnected[i].userToString() + " (" + nodesSayBlownConnected[i].getPeer() + ")").append("\n");
				}
				sb.append("\n");
			}

			if(nodesSayBlownDisconnected.length > 0) {
				sb.append(l10n("disconnectedSayBlownLabel"));

				for(int i = 0; i < nodesSayBlownDisconnected.length; i++) {
					sb.append(nodesSayBlownDisconnected[i].userToString() + " (" + nodesSayBlownDisconnected[i].getPeer() + ")").append("\n");
				}
				sb.append("\n");
			}

			if(nodesSayBlownFailedTransfer.length > 0) {
				sb.append(l10n("failedTransferSayBlownLabel"));

				for(int i = 0; i < nodesSayBlownFailedTransfer.length; i++) {
					sb.append(nodesSayBlownFailedTransfer[i].userToString() + " (" + nodesSayBlownFailedTransfer[i].getPeer() + ")").append('\n');
				}
				sb.append("\n");
			}

			return sb.toString();
		}

		@Override
		public String getTitle() {
			return l10n("titleWithCount", "count", Integer.toString(nodesSayKeyRevoked.size()));
		}

		@Override
		public void isValid(boolean validity) {
			// Do nothing
		}

		@Override
		public boolean isValid() {
			if(updateManager.isBlown()) return false;
			return mightBeRevoked();
		}
		
		@Override
		public String getShortText() {
			return l10n("short");
		}
	}

	public PeerNode[][] getNodesSayBlown() {
		Vector<PeerNode> nodesConnectedSayRevoked = new Vector<PeerNode>();
		Vector<PeerNode> nodesDisconnectedSayRevoked = new Vector<PeerNode>();
		Vector<PeerNode> nodesFailedSayRevoked = new Vector<PeerNode>();
		synchronized(this) {
			PeerNode[] nodesSayRevoked = nodesSayKeyRevoked.toArray(new PeerNode[nodesSayKeyRevoked.size()]);
			for(int i = 0; i < nodesSayRevoked.length; i++) {
				PeerNode pn = nodesSayRevoked[i];
				if(nodesSayKeyRevokedFailedTransfer.contains(pn))
					nodesFailedSayRevoked.add(pn);
				else
					nodesConnectedSayRevoked.add(pn);
			}
		}
		for(int i = 0; i < nodesConnectedSayRevoked.size(); i++) {
			PeerNode pn = nodesConnectedSayRevoked.get(i);
			if(!pn.isConnected()) {
				nodesDisconnectedSayRevoked.add(pn);
				nodesConnectedSayRevoked.remove(i);
				i--;
				continue;
			}
		}
		return new PeerNode[][]{
				nodesConnectedSayRevoked.toArray(new PeerNode[nodesConnectedSayRevoked.size()]),
				nodesDisconnectedSayRevoked.toArray(new PeerNode[nodesDisconnectedSayRevoked.size()]),
				nodesFailedSayRevoked.toArray(new PeerNode[nodesFailedSayRevoked.size()]),
			};
	}

	/**
	 * A peer node requests us to send the binary blob of the revocation key.
	 * @param m The message requesting the transfer.
	 * @param source The node requesting the transfer.
	 * @return True if we handled the message (i.e. always).
	 */
	public boolean handleRequestRevocation(Message m, final PeerNode source) {
		// Do we have the data?

		final RandomAccessThing data = updateManager.revocationChecker.getBlobThing();

		if(data == null) {
			Logger.normal(this, "Peer " + source + " asked us for the blob file for the revocation key but we don't have it!");
			// Probably a race condition on reconnect, hopefully we'll be asked again
			return true;
		}

		final long uid = m.getLong(DMT.UID);

		final PartiallyReceivedBulk prb;
		long length;
		try {
			length = data.size();
			prb = new PartiallyReceivedBulk(updateManager.node.getUSM(), length,
				Node.PACKET_SIZE, data, true);
		} catch(IOException e) {
			Logger.error(this, "Peer " + source + " asked us for the blob file for the revocation key, we have downloaded it but we can't determine the file size: " + e, e);
			data.close();
			return true;
		}

		final BulkTransmitter bt;
		try {
			bt = new BulkTransmitter(prb, source, uid, false, updateManager.ctr, true);
		} catch(DisconnectedException e) {
			Logger.error(this, "Peer " + source + " asked us for the blob file for the revocation key, then disconnected: " + e, e);
			data.close();
			return true;
		}

		final Runnable r = new Runnable() {

			@Override
			public void run() {
				try {
					if(!bt.send())
						Logger.error(this, "Failed to send revocation key blob to " + source.userToString() + " : " + bt.getCancelReason());
					else
						Logger.normal(this, "Sent revocation key blob to " + source.userToString());
				} catch (DisconnectedException e) {
					// Not much we can do here either.
					Logger.warning(this, "Failed to send revocation key blob (disconnected) to " + source.userToString() + " : " + bt.getCancelReason());
				} finally {
					data.close();
				}
			}
		};

		Message msg = DMT.createUOMSendingRevocation(uid, length, updateManager.revocationURI.toString());

		try {
			source.sendAsync(msg, new AsyncMessageCallback() {

				@Override
				public void acknowledged() {
					if(logMINOR)
						Logger.minor(this, "Sending data...");
					// Send the data
					updateManager.node.executor.execute(r, "Revocation key send for " + uid + " to " + source.userToString());
				}

				@Override
				public void disconnected() {
					// Argh
					Logger.error(this, "Peer " + source + " asked us for the blob file for the revocation key, then disconnected when we tried to send the UOMSendingRevocation");
				}

				@Override
				public void fatalError() {
					// Argh
					Logger.error(this, "Peer " + source + " asked us for the blob file for the revocation key, then got a fatal error when we tried to send the UOMSendingRevocation");
				}

				@Override
				public void sent() {
					if(logMINOR)
						Logger.minor(this, "Message sent, data soon");
				}

				@Override
				public String toString() {
					return super.toString() + "(" + uid + ":" + source.getPeer() + ")";
				}
			}, updateManager.ctr);
		} catch(NotConnectedException e) {
			Logger.error(this, "Peer " + source + " asked us for the blob file for the revocation key, then disconnected when we tried to send the UOMSendingRevocation: " + e, e);
			return true;
		}

		return true;
	}

	public boolean handleSendingRevocation(Message m, final PeerNode source) {
		final long uid = m.getLong(DMT.UID);
		final long length = m.getLong(DMT.FILE_LENGTH);
		String key = m.getString(DMT.REVOCATION_KEY);
		
		FreenetURI revocationURI;
		try {
			revocationURI = new FreenetURI(key);
		} catch(MalformedURLException e) {
			Logger.error(this, "Failed receiving recovation because URI not parsable: " + e + " for " + key, e);
			System.err.println("Failed receiving recovation because URI not parsable: " + e + " for " + key);
			e.printStackTrace();
			synchronized(this) {
				// Wierd case of a failed transfer
				// This is definitely not valid, don't add to nodesSayKeyRevokedFailedTransfer.
				nodesSayKeyRevoked.remove(source);
				nodesSayKeyRevokedTransferring.remove(source);
			}
			cancelSend(source, uid);
			maybeNotRevoked();
			return true;
		}

		if(!revocationURI.equals(updateManager.revocationURI)) {
			System.err.println("Node sending us a revocation certificate from the wrong URI:\n" +
				"Node: " + source.userToString() + "\n" +
				"Our   URI: " + updateManager.revocationURI + "\n" +
				"Their URI: " + revocationURI);
			synchronized(this) {
				// Wierd case of a failed transfer
				nodesSayKeyRevoked.remove(source);
				// This is definitely not valid, don't add to nodesSayKeyRevokedFailedTransfer.
				nodesSayKeyRevokedTransferring.remove(source);
			}
			cancelSend(source, uid);
			maybeNotRevoked();
			return true;
		}
		
		if(updateManager.isBlown()) {
			if(logMINOR)
				Logger.minor(this, "Already blown, so not receiving from " + source + "(" + uid + ")");
			cancelSend(source, uid);
			return true;
		}

		if(length > NodeUpdateManager.MAX_REVOCATION_KEY_BLOB_LENGTH) {
			System.err.println("Node " + source.userToString() + " offered us a revocation certificate " + SizeUtil.formatSize(length) + " long. This is unacceptably long so we have refused the transfer. No real revocation cert would be this big.");
			Logger.error(this, "Node " + source.userToString() + " offered us a revocation certificate " + SizeUtil.formatSize(length) + " long. This is unacceptably long so we have refused the transfer. No real revocation cert would be this big.");
			synchronized(UpdateOverMandatoryManager.this) {
				nodesSayKeyRevoked.remove(source);
				nodesSayKeyRevokedTransferring.remove(source);
			}
			cancelSend(source, uid);
			maybeNotRevoked();
			return true;
		}
		
		if(length <= 0) {
			System.err.println("Revocation key is zero bytes from "+source+" - ignoring as this is almost certainly a bug or an attack, it is definitely not valid.");
			synchronized(UpdateOverMandatoryManager.this) {
				nodesSayKeyRevoked.remove(source);
				// This is almost certainly not valid, don't add to nodesSayKeyRevokedFailedTransfer.
				nodesSayKeyRevokedTransferring.remove(source);
			}
			cancelSend(source, uid);
			maybeNotRevoked();
			return true;
		}
		
		System.err.println("Transferring auto-updater revocation certificate length "+length+" from "+source);

		// Okay, we can receive it

		final File temp;

		try {
			temp = File.createTempFile("revocation-", ".fblob.tmp", updateManager.node.clientCore.getPersistentTempDir());
			temp.deleteOnExit();
		} catch(IOException e) {
			System.err.println("Cannot save revocation certificate to disk and therefore cannot fetch it from our peer!: " + e);
			e.printStackTrace();
			updateManager.blow("Cannot fetch the revocation certificate from our peer because we cannot write it to disk: " + e, true);
			cancelSend(source, uid);
			return true;
		}

		RandomAccessFileWrapper raf;
		try {
			raf = new RandomAccessFileWrapper(temp, "rw");
		} catch(FileNotFoundException e) {
			Logger.error(this, "Peer " + source + " asked us for the blob file for the revocation key, we have downloaded it but don't have the file even though we did have it when we checked!: " + e, e);
			updateManager.blow("Internal error after fetching the revocation certificate from our peer, maybe out of disk space, file disappeared "+temp+" : " + e, true);
			return true;
		}
		
		// It isn't starting, it's transferring.
		synchronized(this) {
			nodesSayKeyRevokedTransferring.add(source);
			nodesSayKeyRevoked.remove(source);
		}
		
		PartiallyReceivedBulk prb = new PartiallyReceivedBulk(updateManager.node.getUSM(), length,
			Node.PACKET_SIZE, raf, false);

		final BulkReceiver br = new BulkReceiver(prb, source, uid, updateManager.ctr);

		updateManager.node.executor.execute(new Runnable() {

			@Override
			public void run() {
				try {
				if(br.receive())
					// Success!
					processRevocationBlob(temp, source);
				else {
					Logger.error(this, "Failed to transfer revocation certificate from " + source);
					System.err.println("Failed to transfer revocation certificate from " + source);
					source.failedRevocationTransfer();
					int count = source.countFailedRevocationTransfers();
					boolean retry = count < 3;
					synchronized(UpdateOverMandatoryManager.this) {
						nodesSayKeyRevokedFailedTransfer.add(source);
						nodesSayKeyRevokedTransferring.remove(source);
						if(retry) {
							if(nodesSayKeyRevoked.contains(source))
								retry = false;
							else
								nodesSayKeyRevoked.add(source);
						}
					}
					maybeNotRevoked();
					if(retry) tryFetchRevocation(source);
				}
				} catch (Throwable t) {
					Logger.error(this, "Caught error while transferring revocation certificate from "+source+" : "+t, t);
					System.err.println("Peer "+source+" said that the revocation key has been blown, but we got an internal error while transferring it:");
					t.printStackTrace();
					updateManager.blow("Internal error while fetching the revocation certificate from our peer "+source+" : "+t, true);
					synchronized(UpdateOverMandatoryManager.this) {
						nodesSayKeyRevokedTransferring.remove(source);
					}
				}
			}
		}, "Revocation key receive for " + uid + " from " + source.userToString());

		return true;
	}

	protected void maybeNotRevoked() {
		synchronized(this) {
			if(!updateManager.peersSayBlown()) return;
			if(mightBeRevoked()) return;
			updateManager.notPeerClaimsKeyBlown();
		}
	}

	private boolean mightBeRevoked() {
		PeerNode[] started;
		PeerNode[] transferring;
		synchronized(this) {
			started = nodesSayKeyRevoked.toArray(new PeerNode[nodesSayKeyRevoked.size()]);
			transferring = nodesSayKeyRevokedTransferring.toArray(new PeerNode[nodesSayKeyRevokedTransferring.size()]);
		}
		// If a peer is not connected, ignore it.
		// If a peer has already tried 3 times to send the revocation cert, ignore it,
		// because it is probably evil.
		for(PeerNode peer : started) {
			if(!peer.isConnected()) continue;
			if(peer.countFailedRevocationTransfers() > 3) continue;
			return true;
		}
		for(PeerNode peer : transferring) {
			if(!peer.isConnected()) continue;
			if(peer.countFailedRevocationTransfers() > 3) continue;
			return true;
		}
		return false;
	}

	void processRevocationBlob(final File temp, PeerNode source) {
		processRevocationBlob(new FileBucket(temp, true, false, false, false, true), source.userToString(), false);
	}
	
	/**
	 * Process a binary blob for a revocation certificate (the revocation key).
	 * @param temp The file it was written to.
	 */
	void processRevocationBlob(final Bucket temp, final String source, final boolean fromDisk) {

		SimpleBlockSet blocks = new SimpleBlockSet();

		DataInputStream dis = null;
		try {
			dis = new DataInputStream(new BufferedInputStream(temp.getInputStream()));
			BinaryBlob.readBinaryBlob(dis, blocks, true);
		} catch(FileNotFoundException e) {
			Logger.error(this, "Somebody deleted " + temp + " ? We lost the revocation certificate from " + source + "!");
			System.err.println("Somebody deleted " + temp + " ? We lost the revocation certificate from " + source + "!");
			if(!fromDisk)
				updateManager.blow("Somebody deleted " + temp + " ? We lost the revocation certificate from " + source + "!", true);
			return;
		} catch (EOFException e) {
			Logger.error(this, "Peer " + source + " sent us an invalid revocation certificate! (data too short, might be truncated): " + e + " (data in " + temp + ")", e);
			System.err.println("Peer " + source + " sent us an invalid revocation certificate! (data too short, might be truncated): " + e + " (data in " + temp + ")");
			// Probably malicious, might just be buggy, either way, it's not blown
			e.printStackTrace();
			// FIXME file will be kept until exit for debugging purposes
			return;
		} catch(BinaryBlobFormatException e) {
			Logger.error(this, "Peer " + source + " sent us an invalid revocation certificate!: " + e + " (data in " + temp + ")", e);
			System.err.println("Peer " + source + " sent us an invalid revocation certificate!: " + e + " (data in " + temp + ")");
			// Probably malicious, might just be buggy, either way, it's not blown
			e.printStackTrace();
			// FIXME file will be kept until exit for debugging purposes
			return;
		} catch(IOException e) {
			Logger.error(this, "Could not read revocation cert from temp file " + temp + " from node " + source + " ! : "+e, e);
			System.err.println("Could not read revocation cert from temp file " + temp + " from node " + source + " ! : "+e);
			e.printStackTrace();
			if(!fromDisk)
				updateManager.blow("Could not read revocation cert from temp file " + temp + " from node " + source + " ! : "+e, true);
			// FIXME will be kept until exit for debugging purposes
			return;
		} finally {
			if(dis != null)
				try {
					dis.close();
				} catch(IOException e) {
					// Ignore
				}
		}

		// Fetch our revocation key from the datastore plus the binary blob

		FetchContext seedContext = updateManager.node.clientCore.makeClient((short) 0, true).getFetchContext();
		FetchContext tempContext = new FetchContext(seedContext, FetchContext.IDENTICAL_MASK, true, blocks);
		// If it is too big, we get a TOO_BIG. This is fatal so we will blow, which is the right thing as it means the top block is valid.
		tempContext.maxOutputLength = NodeUpdateManager.MAX_REVOCATION_KEY_LENGTH;
		tempContext.maxTempLength = NodeUpdateManager.MAX_REVOCATION_KEY_TEMP_LENGTH;
		tempContext.localRequestOnly = true;

		File f;
		FileBucket b = null;
		final ArrayBucket cleanedBlob = new ArrayBucket();

		ClientGetCallback myCallback = new ClientGetCallback() {

			@Override
			public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
				if(e.mode == FetchException.CANCELLED) {
					// Eh?
					Logger.error(this, "Cancelled fetch from store/blob of revocation certificate from " + source);
					System.err.println("Cancelled fetch from store/blob of revocation certificate from " + source + " to " + temp + " - please report to developers");
				// Probably best to keep files around for now.
				} else if(e.isFatal()) {
					// Blown: somebody inserted a revocation message, but it was corrupt as inserted
					// However it had valid signatures etc.

					System.err.println("Got revocation certificate from " + source + " (fatal error i.e. someone with the key inserted bad data) : "+e);
					// Blow the update, and propagate the revocation certificate.
					updateManager.revocationChecker.onFailure(e, state, cleanedBlob);
					// Don't delete it if it's from disk, as it's already in the right place.
					if(!fromDisk)
						temp.free();

					insertBlob(updateManager.revocationChecker.getBlobBucket(), "revocation");

				} else {
					Logger.error(this, "Failed to fetch revocation certificate from blob from " + source + " : "+e+" : this is almost certainly bogus i.e. the auto-update is fine but the node is broken.");
					System.err.println("Failed to fetch revocation certificate from blob from " + source + " : "+e+" : this is almost certainly bogus i.e. the auto-update is fine but the node is broken.");
					// This is almost certainly bogus.
					// Delete it, even if it's fromDisk.
					temp.free();
					cleanedBlob.free();
				}
			}
			@Override
			public void onMajorProgress(ObjectContainer container) {
				// Ignore
			}

			@Override
			public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
				System.err.println("Got revocation certificate from " + source);
				updateManager.revocationChecker.onSuccess(result, state, cleanedBlob);
				if(!fromDisk)
					temp.free();
				insertBlob(updateManager.revocationChecker.getBlobBucket(), "revocation");
			}
		};

		ClientGetter cg = new ClientGetter(myCallback,
			updateManager.revocationURI, tempContext, (short) 0, this, null, new BinaryBlobWriter(cleanedBlob), null);

		try {
			updateManager.node.clientCore.clientContext.start(cg);
		} catch(FetchException e1) {
			System.err.println("Failed to decode UOM blob: " + e1);
			e1.printStackTrace();
			myCallback.onFailure(e1, cg, null);
		} catch (DatabaseDisabledException e) {
			// Impossible
		}

	}

	protected void insertBlob(final Bucket bucket, final String type) {
		ClientPutCallback callback = new ClientPutCallback() {

			@Override
			public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
				Logger.error(this, "Failed to insert "+type+" binary blob: " + e, e);
			}
			
			@Override
			public void onFetchable(BaseClientPutter state, ObjectContainer container) {
				// Ignore
			}
			
			@Override
			public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
				// Ignore
			}
			
			@Override
			public void onMajorProgress(ObjectContainer container) {
				// Ignore
			}
			
			@Override
			public void onSuccess(BaseClientPutter state, ObjectContainer container) {
				// All done. Cool.
				Logger.normal(this, "Inserted "+type+" binary blob");
			}

			@Override
			public void onGeneratedMetadata(Bucket metadata,
					BaseClientPutter state, ObjectContainer container) {
				Logger.error(this, "Got onGeneratedMetadata inserting blob from "+state, new Exception("error"));
				metadata.free();
			}
		};
		// We are inserting a binary blob so we don't need to worry about CompatibilityMode etc.
		InsertContext ctx = updateManager.node.clientCore.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS).getInsertContext(true);
		ClientPutter putter = new ClientPutter(callback, bucket,
			FreenetURI.EMPTY_CHK_URI, null, ctx,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS, false, false, this, null, true, updateManager.node.clientCore.clientContext, null, -1);
		try {
			updateManager.node.clientCore.clientContext.start(putter, false);
		} catch(InsertException e1) {
			Logger.error(this, "Failed to start insert of "+type+" binary blob: " + e1, e1);
		} catch (DatabaseDisabledException e) {
			// Impossible
		}
	}

	private void cancelSend(PeerNode source, long uid) {
		Message msg = DMT.createFNPBulkReceiveAborted(uid);
		try {
			source.sendAsync(msg, null, updateManager.ctr);
		} catch(NotConnectedException e1) {
			// Ignore
		}
	}

	public void killAlert() {
		updateManager.node.clientCore.alerts.unregister(alert);
	}

	public void handleRequestJar(Message m, final PeerNode source, final boolean isExt) {
		final String name = isExt ? "ext" : "main";
		
		Message msg;
		final BulkTransmitter bt;
		final RandomAccessFileWrapper raf;

		if (source.isOpennet() && updateManager.dontAllowUOM()) {
			Logger.normal(this, "Peer " + source
					+ " asked us for the blob file for " + name
					+ "; We are a seenode, so we ignore it!");
			return;
		}
		// Do we have the data?

		int version = isExt ? updateManager.newExtJarVersion() : updateManager.newMainJarVersion();
		File data = isExt ? updateManager.getExtBlob(version) : updateManager.getMainBlob(version);

		if(data == null) {
			Logger.normal(this, "Peer " + source + " asked us for the blob file for the "+name+" jar but we don't have it!");
			// Probably a race condition on reconnect, hopefully we'll be asked again
			return;
		}

		final long uid = m.getLong(DMT.UID);

		if(!source.sendingUOMJar(isExt)) {
			Logger.error(this, "Peer "+source+" asked for UOM "+(isExt?"ext":"main")+" jar twice");
			return;
		}
		
		try {
			
			try {
				raf = new RandomAccessFileWrapper(data, "r");
			} catch(FileNotFoundException e) {
				Logger.error(this, "Peer " + source + " asked us for the blob file for the "+name+" jar, we have downloaded it but don't have the file even though we did have it when we checked!: " + e, e);
				return;
			}
			
			final PartiallyReceivedBulk prb;
			long length;
			try {
				length = raf.size();
				prb = new PartiallyReceivedBulk(updateManager.node.getUSM(), length,
						Node.PACKET_SIZE, raf, true);
			} catch(IOException e) {
				Logger.error(this, "Peer " + source + " asked us for the blob file for the "+name+" jar, we have downloaded it but we can't determine the file size: " + e, e);
				raf.close();
				return;
			}
			
			try {
				bt = new BulkTransmitter(prb, source, uid, false, updateManager.ctr, true);
			} catch(DisconnectedException e) {
				Logger.error(this, "Peer " + source + " asked us for the blob file for the "+name+" jar, then disconnected: " + e, e);
				raf.close();
				return;
			}
			
			msg =
				isExt ? DMT.createUOMSendingExtra(uid, length, updateManager.extURI.toString(), version) :
					DMT.createUOMSendingMain(uid, length, updateManager.updateURI.toString(), version);
			
		} catch (RuntimeException e) {
			source.finishedSendingUOMJar(isExt);
			throw e;
		} catch (Error e) {
			source.finishedSendingUOMJar(isExt);
			throw e;
		}
		
		final Runnable r = new Runnable() {

			@Override
			public void run() {
				try {
					if(!bt.send())
						Logger.error(this, "Failed to send "+name+" jar blob to " + source.userToString() + " : " + bt.getCancelReason());
					else
						Logger.normal(this, "Sent "+name+" jar blob to " + source.userToString());
					raf.close();
				} catch (DisconnectedException e) {
					// Not much we can do.
				} finally {
					source.finishedSendingUOMJar(isExt);
					raf.close();
				}
			}
		};

		try {
			source.sendAsync(msg, new AsyncMessageCallback() {

				@Override
				public void acknowledged() {
					if(logMINOR)
						Logger.minor(this, "Sending data...");
					// Send the data

					updateManager.node.executor.execute(r, name+" jar send for " + uid + " to " + source.userToString());
				}

				@Override
				public void disconnected() {
					// Argh
					Logger.error(this, "Peer " + source + " asked us for the blob file for the "+name+" jar, then disconnected when we tried to send the UOMSendingMain");
					source.finishedSendingUOMJar(isExt);
				}

				@Override
				public void fatalError() {
					// Argh
					Logger.error(this, "Peer " + source + " asked us for the blob file for the "+name+" jar, then got a fatal error when we tried to send the UOMSendingMain");
					source.finishedSendingUOMJar(isExt);
				}

				@Override
				public void sent() {
					if(logMINOR)
						Logger.minor(this, "Message sent, data soon");
				}

				@Override
				public String toString() {
					return super.toString() + "(" + uid + ":" + source.getPeer() + ")";
				}
			}, updateManager.ctr);
		} catch(NotConnectedException e) {
			Logger.error(this, "Peer " + source + " asked us for the blob file for the "+name+" jar, then disconnected when we tried to send the UOMSendingExt: " + e, e);
			return;
		} catch (RuntimeException e) {
			source.finishedSendingUOMJar(isExt);
			throw e;
		} catch (Error e) {
			source.finishedSendingUOMJar(isExt);
			throw e;
		}

	}
	
	public boolean handleSendingMain(Message m, final PeerNode source) {
		final long uid = m.getLong(DMT.UID);
		final long length = m.getLong(DMT.FILE_LENGTH);
		String key = m.getString(DMT.MAIN_JAR_KEY);
		final int version = m.getInt(DMT.MAIN_JAR_VERSION);
		final FreenetURI jarURI;
		try {
			jarURI = new FreenetURI(key).setSuggestedEdition(version);
		} catch(MalformedURLException e) {
			Logger.error(this, "Failed receiving main jar " + version + " because URI not parsable: " + e + " for " + key, e);
			System.err.println("Failed receiving main jar " + version + " because URI not parsable: " + e + " for " + key);
			e.printStackTrace();
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendMainJar.remove(source);
			}
			return true;
		}

		if(!jarURI.equals(updateManager.updateURI.setSuggestedEdition(version))) {
			System.err.println("Node sending us a main jar update (" + version + ") from the wrong URI:\n" +
				"Node: " + source.userToString() + "\n" +
				"Our   URI: " + updateManager.updateURI + "\n" +
				"Their URI: " + jarURI);
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendMainJar.remove(source);
			}
			return true;
		}

		if(updateManager.isBlown()) {
			if(logMINOR)
				Logger.minor(this, "Key blown, so not receiving main jar from " + source + "(" + uid + ")");
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendMainJar.remove(source);
			}
			return true;
		}

		if(length > NodeUpdateManager.MAX_MAIN_JAR_LENGTH) {
			System.err.println("Node " + source.userToString() + " offered us a main jar (" + version + ") " + SizeUtil.formatSize(length) + " long. This is unacceptably long so we have refused the transfer.");
			Logger.error(this, "Node " + source.userToString() + " offered us a main jar (" + version + ") " + SizeUtil.formatSize(length) + " long. This is unacceptably long so we have refused the transfer.");
			// If the transfer fails, we don't try again.
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendMainJar.remove(source);
			}
			return true;
		}

		// Okay, we can receive it
		System.out.println("Receiving main jar "+version+" from "+source.userToString());

		final File temp;

		try {
			temp = File.createTempFile("main-", ".fblob.tmp", updateManager.node.clientCore.getPersistentTempDir());
			temp.deleteOnExit();
		} catch(IOException e) {
			System.err.println("Cannot save new main jar to disk and therefore cannot fetch it from our peer!: " + e);
			e.printStackTrace();
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendMainJar.remove(source);
			}
			return true;
		}

		RandomAccessFileWrapper raf;
		try {
			raf = new RandomAccessFileWrapper(temp, "rw");
		} catch(FileNotFoundException e) {
			Logger.error(this, "Peer " + source + " sending us a main jar binary blob, but we lost the temp file " + temp + " : " + e, e);
			synchronized(this) {
				this.nodesAskedSendMainJar.remove(source);
			}
			return true;
		}

		PartiallyReceivedBulk prb = new PartiallyReceivedBulk(updateManager.node.getUSM(), length,
			Node.PACKET_SIZE, raf, false);

		final BulkReceiver br = new BulkReceiver(prb, source, uid, updateManager.ctr);

		updateManager.node.executor.execute(new Runnable() {

			@Override
			public void run() {
				try {
					synchronized(UpdateOverMandatoryManager.class) {
						nodesAskedSendMainJar.remove(source);
						nodesSendingMainJar.add(source);
					}
					if(br.receive())
						// Success!
						processMainJarBlob(temp, source, version, jarURI);
					else {
						Logger.error(this, "Failed to transfer main jar " + version + " from " + source);
						System.err.println("Failed to transfer main jar " + version + " from " + source);
					}
				} finally {
					synchronized(UpdateOverMandatoryManager.class) {
						nodesSendingMainJar.remove(source);
					}
				}
			}
		}, "Main jar (" + version + ") receive for " + uid + " from " + source.userToString());

		return true;
	}

	public boolean handleSendingExt(Message m, final PeerNode source) {
		final long uid = m.getLong(DMT.UID);
		final long length = m.getLong(DMT.FILE_LENGTH);
		String key = m.getString(DMT.EXTRA_JAR_KEY);
		final int version = m.getInt(DMT.EXTRA_JAR_VERSION);
		final FreenetURI jarURI;
		try {
			jarURI = new FreenetURI(key).setSuggestedEdition(version);
		} catch(MalformedURLException e) {
			Logger.error(this, "Failed receiving ext jar " + version + " because URI not parsable: " + e + " for " + key, e);
			System.err.println("Failed receiving ext jar " + version + " because URI not parsable: " + e + " for " + key);
			e.printStackTrace();
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendExtJar.remove(source);
			}
			return true;
		}

		if(!jarURI.equals(updateManager.extURI.setSuggestedEdition(version))) {
			System.err.println("Node sending us a ext jar update (" + version + ") from the wrong URI:\n" +
				"Node: " + source.userToString() + "\n" +
				"Our   URI: " + updateManager.extURI + "\n" +
				"Their URI: " + jarURI);
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendExtJar.remove(source);
			}
			return true;
		}

		if(updateManager.isBlown()) {
			if(logMINOR)
				Logger.minor(this, "Key blown, so not receiving main jar from " + source + "(" + uid + ")");
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendExtJar.remove(source);
			}
			return true;
		}

		if(length > NodeUpdateManager.MAX_MAIN_JAR_LENGTH) {
			System.err.println("Node " + source.userToString() + " offered us a ext jar (" + version + ") " + SizeUtil.formatSize(length) + " long. This is unacceptably long so we have refused the transfer.");
			Logger.error(this, "Node " + source.userToString() + " offered us a ext jar (" + version + ") " + SizeUtil.formatSize(length) + " long. This is unacceptably long so we have refused the transfer.");
			// If the transfer fails, we don't try again.
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendExtJar.remove(source);
			}
			return true;
		}

		// Okay, we can receive it
		System.out.println("Receiving extra jar "+version+" from "+source.userToString());

		final File temp;

		try {
			temp = File.createTempFile("ext-", ".fblob.tmp", updateManager.node.clientCore.getPersistentTempDir());
			temp.deleteOnExit();
		} catch(IOException e) {
			System.err.println("Cannot save new ext jar to disk and therefore cannot fetch it from our peer!: " + e);
			e.printStackTrace();
			cancelSend(source, uid);
			synchronized(this) {
				this.nodesAskedSendExtJar.remove(source);
			}
			return true;
		}

		RandomAccessFileWrapper raf;
		try {
			raf = new RandomAccessFileWrapper(temp, "rw");
		} catch(FileNotFoundException e) {
			Logger.error(this, "Peer " + source + " sending us a ext jar binary blob, but we lost the temp file " + temp + " : " + e, e);
			synchronized(this) {
				this.nodesAskedSendExtJar.remove(source);
			}
			return true;
		}

		PartiallyReceivedBulk prb = new PartiallyReceivedBulk(updateManager.node.getUSM(), length,
			Node.PACKET_SIZE, raf, false);

		final BulkReceiver br = new BulkReceiver(prb, source, uid, updateManager.ctr);

		updateManager.node.executor.execute(new Runnable() {

			@Override
			public void run() {
				try {
					synchronized(UpdateOverMandatoryManager.class) {
						nodesAskedSendExtJar.remove(source);
						nodesSendingExtJar.add(source);
					}
					if(br.receive())
						// Success!
						processExtJarBlob(temp, source, version, jarURI);
					else {
						Logger.error(this, "Failed to transfer ext jar " + version + " from " + source);
						System.err.println("Failed to transfer ext jar " + version + " from " + source);
					}
				} finally {
					synchronized(UpdateOverMandatoryManager.class) {
						nodesSendingExtJar.remove(source);
					}
				}
			}
		}, "Ext jar (" + version + ") receive for " + uid + " from " + source.userToString());

		return true;
	}

	protected void processMainJarBlob(final File temp, final PeerNode source, final int version, FreenetURI uri) {
		SimpleBlockSet blocks = new SimpleBlockSet();

		DataInputStream dis = null;
		try {
			dis = new DataInputStream(new BufferedInputStream(new FileInputStream(temp)));
			BinaryBlob.readBinaryBlob(dis, blocks, true);
		} catch(FileNotFoundException e) {
			Logger.error(this, "Somebody deleted " + temp + " ? We lost the main jar (" + version + ") from " + source.userToString() + "!");
			System.err.println("Somebody deleted " + temp + " ? We lost the main jar (" + version + ") from " + source.userToString() + "!");
			return;
		} catch(IOException e) {
			Logger.error(this, "Could not read main jar (" + version + ") from temp file " + temp + " from node " + source.userToString() + " !");
			System.err.println("Could not read main jar (" + version + ") from temp file " + temp + " from node " + source.userToString() + " !");
			// FIXME will be kept until exit for debugging purposes
			return;
		} catch(BinaryBlobFormatException e) {
			Logger.error(this, "Peer " + source.userToString() + " sent us an invalid main jar (" + version + ")!: " + e, e);
			System.err.println("Peer " + source.userToString() + " sent us an invalid main jar (" + version + ")!: " + e);
			e.printStackTrace();
			// FIXME will be kept until exit for debugging purposes
			return;
		} finally {
			if(dis != null)
				try {
					dis.close();
				} catch(IOException e) {
					// Ignore
				}
		}

		// Fetch the jar from the datastore plus the binary blob

		FetchContext seedContext = updateManager.node.clientCore.makeClient((short) 0, true).getFetchContext();
		FetchContext tempContext = new FetchContext(seedContext, FetchContext.IDENTICAL_MASK, true, blocks);
		tempContext.localRequestOnly = true;

		File f;
		FileBucket b = null;
		try {
			f = File.createTempFile("main-", ".fblob.tmp", updateManager.node.clientCore.getPersistentTempDir());
			f.deleteOnExit();
			b = new FileBucket(f, false, false, true, true, true);
		} catch(IOException e) {
			Logger.error(this, "Cannot share main jar from " + source.userToString() + " with our peers because cannot write the cleaned version to disk: " + e, e);
			System.err.println("Cannot share main jar from " + source.userToString() + " with our peers because cannot write the cleaned version to disk: " + e);
			e.printStackTrace();
			b = null;
			f = null;
		}
		final FileBucket cleanedBlob = b;
		final File cleanedBlobFile = f;

		ClientGetCallback myCallback = new ClientGetCallback() {

			@Override
			public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
				if(e.mode == FetchException.CANCELLED) {
					// Eh?
					Logger.error(this, "Cancelled fetch from store/blob of main jar (" + version + ") from " + source.userToString());
					System.err.println("Cancelled fetch from store/blob of main jar (" + version + ") from " + source.userToString() + " to " + temp + " - please report to developers");
				// Probably best to keep files around for now.
				} else if(e.isFatal()) {
					// Bogus as inserted. Ignore.
					temp.delete();
					Logger.error(this, "Failed to fetch main jar " + version + " from " + source.userToString() + " : fatal error (update was probably inserted badly): " + e, e);
					System.err.println("Failed to fetch main jar " + version + " from " + source.userToString() + " : fatal error (update was probably inserted badly): " + e);
				} else {
					Logger.error(this, "Failed to fetch main jar " + version + " from blob from " + source.userToString());
					System.err.println("Failed to fetch main jar " + version + " from blob from " + source.userToString());
				}
			}

			@Override
			public void onMajorProgress(ObjectContainer container) {
				// Ignore
			}

			@Override
			public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
				System.err.println("Got main jar version " + version + " from " + source.userToString());
				if(result.size() == 0) {
					System.err.println("Ignoring because 0 bytes long");
					return;
				}

				NodeUpdater mainUpdater = updateManager.mainUpdater;
				if(mainUpdater == null) {
					System.err.println("Not updating because updater is disabled!");
					return;
				}
				mainUpdater.onSuccess(result, state, cleanedBlobFile, version);
				temp.delete();
				insertBlob(mainUpdater.getBlobBucket(version), "main jar");
			}
		};

		ClientGetter cg = new ClientGetter(myCallback,
			uri, tempContext, (short) 0, this, null, new BinaryBlobWriter(cleanedBlob), null);

		try {
			updateManager.node.clientCore.clientContext.start(cg);
		} catch(FetchException e1) {
			myCallback.onFailure(e1, cg, null);
		} catch (DatabaseDisabledException e) {
			// Impossible
		}

	}

	protected void processExtJarBlob(final File temp, final PeerNode source, final int version, FreenetURI uri) {
		SimpleBlockSet blocks = new SimpleBlockSet();

		DataInputStream dis = null;
		try {
			dis = new DataInputStream(new BufferedInputStream(new FileInputStream(temp)));
			BinaryBlob.readBinaryBlob(dis, blocks, true);
		} catch(FileNotFoundException e) {
			Logger.error(this, "Somebody deleted " + temp + " ? We lost the ext jar (" + version + ") from " + source.userToString() + "!");
			System.err.println("Somebody deleted " + temp + " ? We lost the ext jar (" + version + ") from " + source.userToString() + "!");
			return;
		} catch(IOException e) {
			Logger.error(this, "Could not read ext jar (" + version + ") from temp file " + temp + " from node " + source.userToString() + " !");
			System.err.println("Could not read ext jar (" + version + ") from temp file " + temp + " from node " + source.userToString() + " !");
			// FIXME will be kept until exit for debugging purposes
			return;
		} catch(BinaryBlobFormatException e) {
			Logger.error(this, "Peer " + source.userToString() + " sent us an invalid ext jar (" + version + ")!: " + e, e);
			System.err.println("Peer " + source.userToString() + " sent us an invalid ext jar (" + version + ")!: " + e);
			e.printStackTrace();
			// FIXME will be kept until exit for debugging purposes
			return;
		} finally {
			if(dis != null)
				try {
					dis.close();
				} catch(IOException e) {
					// Ignore
				}
		}

		// Fetch the jar from the datastore plus the binary blob

		FetchContext seedContext = updateManager.node.clientCore.makeClient((short) 0, true).getFetchContext();
		FetchContext tempContext = new FetchContext(seedContext, FetchContext.IDENTICAL_MASK, true, blocks);
		tempContext.localRequestOnly = true;

		File f;
		FileBucket b = null;
		try {
			f = File.createTempFile("ext-", ".fblob.tmp", updateManager.node.clientCore.getPersistentTempDir());
			f.deleteOnExit();
			b = new FileBucket(f, false, false, true, true, true);
		} catch(IOException e) {
			Logger.error(this, "Cannot share ext jar from " + source.userToString() + " with our peers because cannot write the cleaned version to disk: " + e, e);
			System.err.println("Cannot share ext jar from " + source.userToString() + " with our peers because cannot write the cleaned version to disk: " + e);
			e.printStackTrace();
			b = null;
			f = null;
		}
		final FileBucket cleanedBlob = b;
		final File cleanedBlobFile = f;

		ClientGetCallback myCallback = new ClientGetCallback() {

			@Override
			public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
				if(e.mode == FetchException.CANCELLED) {
					// Eh?
					Logger.error(this, "Cancelled fetch from store/blob of ext jar (" + version + ") from " + source.userToString());
					System.err.println("Cancelled fetch from store/blob of ext jar (" + version + ") from " + source.userToString() + " to " + temp + " - please report to developers");
				// Probably best to keep files around for now.
				} else if(e.isFatal()) {
					// Bogus as inserted. Ignore.
					temp.delete();
					Logger.error(this, "Failed to fetch ext jar " + version + " from " + source.userToString() + " : fatal error (update was probably inserted badly): " + e, e);
					System.err.println("Failed to fetch ext jar " + version + " from " + source.userToString() + " : fatal error (update was probably inserted badly): " + e);
				} else {
					Logger.error(this, "Failed to fetch ext jar " + version + " from blob from " + source.userToString());
					System.err.println("Failed to fetch ext jar " + version + " from blob from " + source.userToString());
				}
			}

			@Override
			public void onMajorProgress(ObjectContainer container) {
				// Ignore
			}

			@Override
			public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
				System.err.println("Got ext jar version " + version + " from " + source.userToString());
				if(result.size() == 0) {
					System.err.println("Ignoring because 0 bytes long");
					return;
				}

				NodeUpdater extUpdater = updateManager.extUpdater;
				if(extUpdater == null) {
					System.err.println("Not updating because ext updater is disabled!");
					return;
				}
				extUpdater.onSuccess(result, state, cleanedBlobFile, version);
				temp.delete();
				insertBlob(extUpdater.getBlobBucket(version), "ext jar");
			}
		};

		ClientGetter cg = new ClientGetter(myCallback,
				uri, tempContext, (short) 0, this, null, new BinaryBlobWriter(cleanedBlob), null);

			try {
				updateManager.node.clientCore.clientContext.start(cg);
			} catch(FetchException e1) {
				myCallback.onFailure(e1, cg, null);
			} catch (DatabaseDisabledException e) {
				// Impossible
			}

	}

	protected boolean removeOldTempFiles() {
		File oldTempFilesPeerDir = updateManager.node.clientCore.getPersistentTempDir();
		if(!oldTempFilesPeerDir.exists())
			return false;
		if(!oldTempFilesPeerDir.isDirectory()) {
			Logger.error(this, "Persistent temporary files location is not a directory: " + oldTempFilesPeerDir.getPath());
			return false;
		}

		boolean gotError = false;
		File[] oldTempFiles = oldTempFilesPeerDir.listFiles(new FileFilter() {

			private final int lastGoodMainBuildNumber = Version.lastGoodBuild();
			private final int recommendedExtBuildNumber = NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER;

			@Override
			public boolean accept(File file) {
				String fileName = file.getName();

				if(fileName.startsWith("revocation-") && fileName.endsWith(".fblob.tmp"))
					return true;

				String buildNumberStr;
				int buildNumber;
				Matcher extBuildNumberMatcher = extBuildNumberPattern.matcher(fileName);
				Matcher mainBuildNumberMatcher = mainBuildNumberPattern.matcher(fileName);
				Matcher extTempBuildNumberMatcher = extTempBuildNumberPattern.matcher(fileName);
				Matcher mainTempBuildNumberMatcher = mainTempBuildNumberPattern.matcher(fileName);
				Matcher revocationTempBuildNumberMatcher = revocationTempBuildNumberPattern.matcher(fileName);

				if(mainBuildNumberMatcher.matches()) {
					try {
					buildNumberStr = mainBuildNumberMatcher.group(1);
					buildNumber = Integer.parseInt(buildNumberStr);
					if(buildNumber < lastGoodMainBuildNumber)
						return true;
					} catch (NumberFormatException e) {
						Logger.error(this, "Wierd file in persistent temp: "+fileName);
						return false;
					}
				} else if(extBuildNumberMatcher.matches()) {
					try {
					buildNumberStr = extBuildNumberMatcher.group(1);
					buildNumber = Integer.parseInt(buildNumberStr);
					if(buildNumber < recommendedExtBuildNumber)
						return true;
					} catch (NumberFormatException e) {
						Logger.error(this, "Wierd file in persistent temp: "+fileName);
						return false;
					}
				} else if(mainTempBuildNumberMatcher.matches() || extTempBuildNumberMatcher.matches() || revocationTempBuildNumberMatcher.matches()) {
					// Temporary file, can be deleted
					return true;
				}

				return false;
			}
		});

		for(File fileToDelete : oldTempFiles) {
			String fileToDeleteName = fileToDelete.getName();
			if(!fileToDelete.delete()) {
				if(fileToDelete.exists())
					Logger.error(this, "Cannot delete temporary persistent file " + fileToDeleteName + " even though it exists: must be TOO persistent :)");
				else
					Logger.normal(this, "Temporary persistent file does not exist when deleting: " + fileToDeleteName);
				gotError = true;
			}
		}

		return !gotError;
	}

	@Override
	public boolean persistent() {
		return false;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	public void disconnected(PeerNode pn) {
		synchronized(this) {
			nodesSayKeyRevoked.remove(pn);
			nodesSayKeyRevokedFailedTransfer.remove(pn);
			nodesSayKeyRevokedTransferring.remove(pn);
			nodesOfferedMainJar.remove(pn);
			nodesOfferedExtJar.remove(pn);
			nodesAskedSendMainJar.remove(pn);
			nodesAskedSendExtJar.remove(pn);
			nodesSendingMainJar.remove(pn);
			nodesSendingExtJar.remove(pn);
		}
		maybeNotRevoked();
	}

	public boolean fetchingFromTwo() {
		synchronized(this) {
			return (this.nodesSendingMainJar.size() + this.nodesSendingExtJar.size()) >= 2;
		}
	}

	@Override
	public boolean realTimeFlag() {
		return false;
	}

	public boolean isFetchingMain() {
		synchronized(this) {
			return nodesSendingMainJar.size() > 0;
		}
	}

	public boolean isFetchingExt() {
		synchronized(this) {
			return nodesSendingExtJar.size() > 0;
		}
	}
}
