/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.node;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.support.api.LongCallback;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.HashMap;

/**
 * This class is responsible for tracking the proof of work performed
 * by each of our opennet peers. We typically consider each successfully
 * fetched CHK requests one unit of work, regardless of whether it was
 * a CHK we requested, or a CHK we forwarded on behalf of someone else.
 * We then use the proof of work performed by each of our connected peers
 * when deciding which peers we actually send out our own local requests to.
 * This is important to make de-anonymization attacks hard. Someone must
 * devote quite a bit of bandwidth over an extended period of time before
 * they can get to see our own local requests and even start trying to
 * de-anonymize us. After that they still need to be able to figure out
 * which HTL 17 and HTL 18 requests that are ours and which ones we are
 * simply forwarding. Since everyone on the network require proof of work,
 * large scale de-anonymization attacks are largely prevented by being
 * too costly. To raise the cost further, we remember the proof of work
 * done by our peers between Freenet restarts and upgrades. We call the
 * proof of work scores "opennet peer trust scores".
 *
 * This proof of work scheme not only increases security, it also creates
 * an incentive to run faster nodes, since that will earn you higher level
 * of security. Since we use successful CHK responses as the indication
 * of work, there is also an incentive to run nodes with larger caches,
 * and to not block keys. Even an attacker must cooperate well with the
 * network to not be hindered in their attack attempts, which creates a
 * dilemma for them.
 *
 * We do not count successful transfer of offered CHK keys though, since
 * the attacker potentially can affect what they offer. Most CHK requests
 * are not from offered keys anyway.
 *
 * We also permit the user to configure a minimum permitted trust score.
 * Usually we send local requests to the 50% of currently connected peers
 * who have the highest score, but if an attacker can surround us and take
 * the place of our regular peers, the median score could drop all the way
 * down to zero and the attacker can start seeing local requests without
 * having done any proof of work. By never sending local requests to peers
 * that have a score below a certain configured minimum, even if there are
 * no other peers, such de-anonymization attacks are converted into the more
 * harmless denial of service attacks. This is for advanced users with high
 * anonymity requirements only.
 */
public class TrustScoreManager {
	/** The file name for the opennet peer trust score file */
	private static final String TRUST_FILE_NAME = "opennet-trust.db";
	/** Save file after this many score increments since last save */
	private static final long SAVE_EVERY_INCR = 100;

	/**
	 * A peer entry in the peer trust score file.
	 * Including code to serialize and unserialize this object
	 * into a single text line suitable for storage in a file.
	 */
	private class PeerTrust {
		byte[] pubKeyHash;
		long score;

		PeerTrust(byte[] pubKeyHash) {
			this.pubKeyHash = pubKeyHash;
			this.score = 0;
		}

		PeerTrust(String line) throws IOException {
			String[] fields = line.split(",");
			if (fields.length != 2) {
				throw new IOException("wrong field count");
			}

			try {
				pubKeyHash = Base64.decode(fields[0]);
			} catch (IllegalBase64Exception e) {
				throw new IOException("identity not base64");
			}

			if (pubKeyHash.length != 32) {
				throw new IOException("identity wrong size");
			}

			try {
				score = Long.parseLong(fields[1]);
			} catch (NumberFormatException e) {
				throw new IOException("score not an integer");
			}
		}

		@Override
		public String toString() {
			return Base64.encode(pubKeyHash) + "," + score;
		}

		@Override
		public final int hashCode() {
			return Fields.hashCode(pubKeyHash);
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof PeerTrust) {
				PeerTrust pt = (PeerTrust) o;
				return Arrays.equals(pt.pubKeyHash,
					pubKeyHash);
			} else {
				return false;
			}
		}
	}

	/** All peers we ever added any score for, score only in value */
	private HashMap<PeerTrust,PeerTrust> peers;
	/** Median score of all currently connected opennet peers */
	private long medianScore;
	/** Minimum permitted score, if configured by the user */
	private long minScore;
	/** Do not recalculate scores if peer list still is this one */
	private PeerNode[] calculatedFor;
	/** Number of score increments done since last save */
	private int sinceLastSave;

	/**
	 * Load opennet peer trust scores from file, or start with
	 * a fresh one if the file does not exist.
	 */
	public TrustScoreManager(SemiOrderedShutdownHook shutdownHook,
			SubConfig config) {
		peers = new HashMap<PeerTrust,PeerTrust>();
		medianScore = -1;
		minScore = 0;
		calculatedFor = null;
		sinceLastSave = 0;

		shutdownHook.addEarlyJob(new Thread() {
			public void run() {
				writeFile();
			}
		});

		config.register("minimumTrustScore", 0, 0, false, false,
				"Node.minimumTrustScore",
				"Node.minimumTrustScoreLong",
				new LongCallback() {
			@Override
			public Long get() {
				return minScore;
			}

			@Override
			public void set(Long score) throws InvalidConfigValueException {
				synchronized (TrustScoreManager.this) {
					long newScore = score;
					if (newScore < 0) newScore = 0;
					minScore = newScore;
				}
			}
		}, false);

		minScore = config.getLong("minimumTrustScore");

		File realfile = new File(TRUST_FILE_NAME);
		if (!realfile.exists()) {
			return;
		}

		try {
			FileInputStream is = new FileInputStream(realfile);
			BufferedInputStream bis = new BufferedInputStream(is);
			InputStreamReader isr = new InputStreamReader(bis,
				"UTF-8");
			BufferedReader br = new BufferedReader(isr);

			while (true) {
				String line = br.readLine();
				if (line == null) {
					break;
				}
				if (line.startsWith("#")) {
					continue;
				}

				PeerTrust trust = new PeerTrust(line);
				peers.put(trust, trust);
			}
		} catch (IOException e) {
			Logger.error(this, "Exception while reading " +
				TRUST_FILE_NAME, e);
		}
	}

	/**
	 * Save updated file with peer trust scores, with atomic replace
	 * to deal gracefully with power failures. Either the old file is
	 * still there, or the new one is, never a partially written file.
	 *
	 * This is called periodically, and during shutdown.
	 */
	private void writeFile() {
		File realfile = new File(TRUST_FILE_NAME);
		File tmpfile = new File(TRUST_FILE_NAME + ".tmp");

		try {
			FileOutputStream os = new FileOutputStream(tmpfile);
			OutputStreamWriter osw = new OutputStreamWriter(os,
				"UTF-8");
			BufferedWriter bw = new BufferedWriter(osw);

			bw.write("# Do not edit this file, auto-generated\n");
			bw.write("# The file contains peer trust scores\n");

			Iterator<PeerTrust> iter = peers.values().iterator();
			while (iter.hasNext()) {
				PeerTrust peer = iter.next();
				bw.write(peer.toString() + "\n");
			}

			bw.flush();
			os.getFD().sync();
			bw.close();

			tmpfile.renameTo(realfile);
		} catch (IOException e) {
			Logger.error(this, "Exception while writing " +
				TRUST_FILE_NAME, e);
		}

		sinceLastSave = 0;
	}

	/**
	 * Fetch the trust score for a certain peer.
	 * If we do not know about this peer yet, assume a score of 0.
	 */
	public synchronized long getScoreFor(PeerNode peerNode) {
		byte[] identifier = peerNode.getPubKeyHash();
		PeerTrust query = new PeerTrust(identifier);
		PeerTrust value = peers.get(query);
		if (value == null) {
			return 0;
		}

		return value.score;
	}

	/**
	 * Recalculate the median peer trust score for all connected
	 * opennet peers whenever a new peer have connected, an old one
	 * disconnected, or a peer that was below the currently calculated
	 * median proof of work just went above it.
	 */
	private void recalculateMedianScore(PeerNode[] connectedNodes) {
		long[] scores = new long[connectedNodes.length];

		for (int i = 0; i < connectedNodes.length; i++) {
			byte[] identifier = connectedNodes[i].getPubKeyHash();
			PeerTrust query = new PeerTrust(identifier);
			PeerTrust value = peers.get(query);
			if (value == null) {
				value = query;
			}

			scores[i] = value.score;
		}

		Arrays.sort(scores);
		medianScore = scores[scores.length / 2];
		calculatedFor = connectedNodes;
	}

	/**
	 * Return true if this peer is a darknet peer, thus trusted,
	 * or if this peer one of the 50% connected opennet peers with
	 * the highest proof of work score and that score is above the
	 * minimum permitted score, thus marginally trusted.
	 */
	public synchronized boolean trustedForLocalRequests(PeerNode peerNode,
			PeerNode[] connectedNodes) {
		if (peerNode.isDarknet()) {
			return true;
		}

		long score = getScoreFor(peerNode);

		if (connectedNodes != null) {
			if (calculatedFor != connectedNodes) {
				recalculateMedianScore(connectedNodes);
			}
		}

		if (medianScore >= 0 && score >= medianScore &&
				score >= minScore) {
			return true;
		}

		return false;
	}

	/**
	 * Increase the opennet trust score for this peer by one unit,
	 * because it just successfully satisfied a CHK data request
	 * originating from or forwarded by us. It is fine if the peer
	 * is a darknet peer, we still increase our trust score for it.
	 *
	 * It is important that we never are connected to two peers
	 * at the same time that have the same public key hash. That
	 * would allow an attacker to amplify the speed at which they
	 * acquire scores. The PeerManager class already ensured this
	 * cannot happen in addPeer(), by calling PeerNode's equal()
	 * method that compares the public key hashes.
	 */
	public synchronized void increaseScoreFor(PeerNode peerNode) {
		byte[] identifier = peerNode.getPubKeyHash();
		PeerTrust query = new PeerTrust(identifier);
		PeerTrust value = peers.get(query);
		if (value == null) {
			value = query;
			peers.put(query, value);
		}

		value.score++;
		if (value.score == medianScore) {
			calculatedFor = null;
		}

		sinceLastSave++;
		if (sinceLastSave >= SAVE_EVERY_INCR) {
			writeFile();
		}
	}
}
