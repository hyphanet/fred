package freenet.node.probe;

import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.SubConfig;
import freenet.io.comm.AsyncMessageFilterCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.node.Node;
import freenet.node.OpennetManager;
import freenet.node.PeerNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.api.BooleanCallback;
import freenet.support.api.LongCallback;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Handles starting, routing, and responding to Metropolis-Hastings corrected probes.
 *
 * Possible future additions to these probes' results include:
 * <ul>
 * <li>Starting a regular request for a key.</li>
 * <li>Success rates for remote requests by HTL; perhaps over some larger amount of time than the past hour.</li>
 * </ul>
 *
 * @see freenet.node.probe Explanation of Metropolis-Hastings correction
 */
public class Probe implements ByteCounter {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	private static volatile boolean logWARNING;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logWARNING = Logger.shouldLog(Logger.LogLevel.WARNING, this);
				logMINOR = Logger.shouldLog(Logger.LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(Logger.LogLevel.DEBUG, this);
			}
		});
	}

	private final static String SOURCE_DISCONNECT = "Previous step in probe chain no longer connected.";

	/**
	 * Maximum hopsToLive value to clamp requests to.
	 */
	public static final byte MAX_HTL = 70;

	/**
	 * Maximum number of forwarding attempts to make before failing with DISCONNECTED.
	 */
	public static final int MAX_SEND_ATTEMPTS = 50;

	/**
	 * Probability of HTL decrement at HTL = 1.
	 */
	public static final float DECREMENT_PROBABILITY = 0.2f;

	/**
	 * In ms, per HTL above HTL = 1.
	 */
	public static final int TIMEOUT_PER_HTL = 3000;

	/**
	 * In ms, to account for probabilistic decrement at HTL = 1.
	 */
	public static final int TIMEOUT_HTL1 = (int)(TIMEOUT_PER_HTL / DECREMENT_PROBABILITY);

	/**
	 * Minute in milliseconds.
	 */
	private static final long MINUTE = 60 * 1000;

	/**
	 * To make the timing less obvious when a node responds with a local result instead of forwarding at
	 * HTL = 1, delay for a number of milliseconds, specifically an exponential distribution with this constant as
	 * its mean.
	 */
	public static final long WAIT_BASE = 1000L;

	/**
	 * Maximum number of milliseconds to wait before sending a response.
	 */
	public static final long WAIT_MAX = 2000L;

	/**
	 * Maximum number of probes accepted from a single peer in the past minute.
	 */
	public final int COUNTER_MAX_PEER = 10;

	/**
	 * Maximum number of probes started locally in the past minute. This is the maximum conceivable value; the
	 * probes should be used with a number of requests per minute closer to the per-peer limit times the minimum
	 * expected number of peers. Around this value, and certainly above it, remote OVERLOADs may start coming
	 * in, which are not useful. The Metropolis-Hastings correction makes behavior potentially inconsistent, so
	 * keeping an eye on remote OVERLOADs is wise.
	 */
	public final int COUNTER_MAX_LOCAL = COUNTER_MAX_PEER * OpennetManager.MAX_PEERS_FOR_SCALING;

	/**
	 * Number of accepted probes in the last minute, keyed by peer.
	 */
	private final Map<PeerNode, Counter> accepted;

	private final Node node;

	private final Timer timer;

	//Whether to respond to different types of probe requests.
	private volatile boolean respondBandwidth;
	private volatile boolean respondBuild;
	private volatile boolean respondIdentifier;
	private volatile boolean respondLinkLengths;
	private volatile boolean respondLocation;
	private volatile boolean respondStoreSize;
	private volatile boolean respondUptime;

	private volatile long probeIdentifier;

	/**
	 * Applies multiplicative Gaussian noise of mean 1.0 and the specified sigma to the input value.
	 * @param input Value to apply noise to.
	 * @param sigma Percentage change at one standard deviation.
	 * @return Value +/- Gaussian percentage.
	 */
	private final double randomNoise(final double input, final double sigma) {
		return node.nodeStats.randomNoise(input, sigma);
	}

	/**
	 * Counts as probe request transfer.
	 * @param bytes Bytes received.
	 */
	@Override
	public void sentBytes(int bytes) {
		node.nodeStats.probeRequestCtr.sentBytes(bytes);
	}

	/**
	 * Counts as probe request transfer.
	 * @param bytes Bytes received.
	 */
	@Override
	public void receivedBytes(int bytes) {
		node.nodeStats.probeRequestCtr.receivedBytes(bytes);
	}

	/**
	 * No payload in probes.
	 * @param bytes Ignored.
	 */
	@Override
	public void sentPayload(int bytes) {}

	public Probe(final Node node) {
		this.node = node;
		this.accepted = Collections.synchronizedMap(new HashMap<PeerNode, Counter>());
		this.timer = new Timer(true);

		int sortOrder = 0;
		final SubConfig nodeConfig = node.config.get("node");

		nodeConfig.register("probeBandwidth", true, sortOrder++, true, true, "Node.probeBandwidthShort",
			"Node.probeBandwidthLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondBandwidth;
			}

			@Override
			public void set(Boolean val) {
				respondBandwidth = val;
			}
		});
		respondBandwidth = nodeConfig.getBoolean("probeBandwidth");
		nodeConfig.register("probeBuild", true, sortOrder++, true, true, "Node.probeBuildShort",
			"Node.probeBuildLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondBuild;
			}

			@Override
			public void set(Boolean val) {
				respondBuild = val;
			}
		});
		respondBuild = nodeConfig.getBoolean("probeBuild");
		nodeConfig.register("probeIdentifier", true, sortOrder++, true, true,
			"Node.probeRespondIdentifierShort", "Node.probeRespondIdentifierLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondIdentifier;
			}

			@Override
			public void set(Boolean val) {
				respondIdentifier = val;
			}
		});
		respondIdentifier = nodeConfig.getBoolean("probeIdentifier");
		nodeConfig.register("probeLinkLengths", true, sortOrder++, true, true, "Node.probeLinkLengthsShort",
			"Node.probeLinkLengthsLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondLinkLengths;
			}

			@Override
			public void set(Boolean val) {
				respondLinkLengths = val;
			}
		});
		respondLinkLengths = nodeConfig.getBoolean("probeLinkLengths");
		nodeConfig.register("probeLocation", true, sortOrder++, true, true, "Node.probeLocationShort",
			"Node.probeLocationLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondLocation;
			}

			@Override
			public void set(Boolean val) {
				respondLocation = val;
			}
		});
		respondLocation = nodeConfig.getBoolean("probeLocation");
		nodeConfig.register("probeStoreSize", true, sortOrder++, true, true, "Node.probeStoreSizeShort",
			"Node.probeStoreSizeLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondStoreSize;
			}

			@Override
			public void set(Boolean val) {
				respondStoreSize = val;
			}
		});
		respondStoreSize = nodeConfig.getBoolean("probeStoreSize");
		nodeConfig.register("probeUptime", true, sortOrder++, true, true, "Node.probeUptimeShort",
			"Node.probeUptimeLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondUptime;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				respondUptime = val;
			}
		});
		respondUptime = nodeConfig.getBoolean("probeUptime");

		nodeConfig.register("identifier", -1, sortOrder++, true, true, "Node.probeIdentifierShort",
			"Node.probeIdentifierLong", new LongCallback() {
			@Override
			public Long get() {
				return probeIdentifier;
			}

			@Override
			public void set(Long val) {
				probeIdentifier = val;
				//-1 is reserved for picking a random value; don't pick it randomly.
				while(probeIdentifier == -1) probeIdentifier = node.random.nextLong();
			}
		}, false);
		probeIdentifier = nodeConfig.getLong("identifier");

		/*
		 * set() is not used when setting up an option with its default value, so do so manually to avoid using
		 * an identifier of -1.
		 */
		try {
			if(probeIdentifier == -1) {
				nodeConfig.getOption("identifier").setValue("-1");
				//TODO: Store config here as it has changed?
				node.config.store();
			}
		} catch (InvalidConfigValueException e) {
			Logger.error(Probe.class, "node.identifier set() unexpectedly threw.", e);
		} catch (NodeNeedRestartException e) {
			Logger.error(Probe.class, "node.identifier set() unexpectedly threw.", e);
		}
	}

	/**
	 * Sends an outgoing probe request.
	 * @param htl htl for this outgoing probe: should be [1, MAX_HTL]
	 * @param listener will be called with results.
	 * @see Listener
	 */
	public void start(final byte htl, final long uid, final Type type, final Listener listener) {
		request(DMT.createProbeRequest(htl, uid, type), null, listener);
	}

	/**
	 * Processes an incoming probe request; relays results back to source.
	 * If the probe has a positive HTL, routes with MH correction and probabilistically decrements HTL.
	 * If the probe comes to have an HTL of zero: (an incoming HTL of less than one is discarded.)
	 * Returns (as node settings allow) exactly one of:
	 * <ul>
	 *         <li>unique identifier and integer 7-day uptime percentage</li>
	 *         <li>uptime: 48-hour percentage or 7-day percentage</li>
	 *         <li>output bandwidth</li>
	 *         <li>store size</li>
	 *         <li>link lengths</li>
	 *         <li>location</li>
	 *         <li>build number</li>
	 * </ul>
	 *
	 * @param message probe request, containing HTL
	 */
	public void request(Message message, PeerNode source) {
		request(message, source, new ResultRelay(source, message.getLong(DMT.UID)));
	}

	/**
	 * Processes a probe request, calling the listener with any results.
	 * @param source node from which the probe request was received. If null, it is considered to have been sent
	 * by the local node.
	 * @param listener listener for probe response.
	 */
	private void request(final Message message, final PeerNode source, final Listener listener) {
		final Long uid = message.getLong(DMT.UID);
		final byte typeCode = message.getByte(DMT.TYPE);
		final Type type;
		if (Type.isValid(typeCode)) {
			type = Type.valueOf(typeCode);
			if (logDEBUG) Logger.debug(Probe.class, "Probe type is " + type.name() + ".");
		} else {
			if (logMINOR) Logger.minor(Probe.class, "Invalid probe type " + typeCode + ".");
			listener.onError(Error.UNRECOGNIZED_TYPE, typeCode, true);
			return;
		}
		byte htl = message.getByte(DMT.HTL);
		if (htl < 1) {
			if (logWARNING) {
				Logger.warning(Probe.class, "Received out-of-bounds HTL of " + htl + " from " +
				    source.getIdentityString() + " (" + source.userToString() + "); discarding.");
			}
			return;
		} else if (htl > MAX_HTL) {
			if (logMINOR) {
				Logger.minor(Probe.class, "Received out-of-bounds HTL of " + htl + " from " +
				    source.getIdentityString() + " (" + source.userToString() + "); interpreting as " +
				    MAX_HTL + ".");
			}
			htl = MAX_HTL;
		}
		boolean availableSlot = true;
		TimerTask task = null;
		//Allocate one of this peer's probe request slots for 60 seconds; send an overload if none are available.
		synchronized (accepted) {
			//If no counter exists for the current source, add one.
			if (!accepted.containsKey(source)) {
				// Null source is started locally.
				accepted.put(source, new Counter(source == null ? COUNTER_MAX_LOCAL : COUNTER_MAX_PEER));
			}
			final Counter counter = accepted.get(source);
			if (counter.value() == counter.maxAccepted) {
				//Set a flag instead of sending inside the lock.
				availableSlot = false;
			} else {
				//There's a free slot; increment the counter.
				counter.increment();
				task = new TimerTask() {
					@Override
					public void run() {
						synchronized (accepted) {
							counter.decrement();
							/* Once the counter hits zero, there's no reason to keep it around as it
							 * can just be recreated when this peer sends another probe request
							 * without changing behavior. To do otherwise would accumulate counters
							 * at zero over time.
							 */
							if (counter.value() == 0) {
								accepted.remove(source);
							}
						}
					}
				};
			}
		}
		if (!availableSlot) {
			//Send an overload error back to the source.
			if (logDEBUG) Logger.debug(Probe.class, "Already accepted maximum number of probes; rejecting incoming.");
			listener.onError(Error.OVERLOAD, null, true);
			return;
		}
		//One-minute window on acceptance; free up this probe slot in 60 seconds.
		timer.schedule(task, MINUTE);

		/*
		 * Route to a peer, using Metropolis-Hastings correction and ignoring backoff to get a more uniform
		 * endpoint distribution. HTL is decremented before routing so that it's possible to respond locally without
		 * attempting to route first. Send a local response if HTL is zero now or becomes zero while trying to route.
		 * During routing HTL decrements if a candidate is rejected by the Metropolis-Hastings correction.
		 */
		htl = probabilisticDecrement(htl);
		if (htl == 0 || !route(type, uid, htl, listener)) {
			long wait = WAIT_MAX;
			while (wait >= WAIT_MAX) wait = (long)(-Math.log(node.random.nextDouble()) * WAIT_BASE / Math.E);
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					respond(type, listener);
				}
			}, wait);
		}
	}

	/**
	 * Attempts to route the message to a peer. If the maximum number of send attempts is exceeded, fails with the
	 * error CANNOT_FORWARD.
	 * @return True if no further action needed; false if HTL decremented to zero and a local response is needed.
	 */
	private boolean route(final Type type, final long uid, byte htl, final Listener listener) {
		//Recreate the request so that any sub-messages or unintended fields are not forwarded.
		final Message message = DMT.createProbeRequest(htl, uid, type);
		PeerNode[] peers;
		//Degree of the local node.
		int degree;
		PeerNode candidate;
		/*
		 * Attempt to forward until success or until reaching the send attempt limit.
		 */
		for (int sendAttempts = 0; sendAttempts < MAX_SEND_ATTEMPTS; sendAttempts++) {
			peers = node.getConnectedPeers();
			degree = peers.length;
			//Can't handle a probe request if not connected to peers.
			if (degree == 0 ) {
				if (logMINOR) {
					Logger.minor(Probe.class, "Aborting probe request: no connections.");
				}

				/*
				 * If this is a locally-started request, not a relayed one, give an error.
				 * Otherwise, in this case there's nowhere to send the error.
				 */
				listener.onError(Error.DISCONNECTED, null, true);
				return true;
			}

			candidate = peers[node.random.nextInt(degree)];

			if (candidate.isConnected()) {
				//acceptProbability is the MH correction.
				float acceptProbability;
				int candidateDegree = candidate.getDegree();
				/* Candidate's degree is unknown; fall back to random walk by accepting this candidate
				 * regardless of its degree.
				 */
				if (candidateDegree == 0) acceptProbability = 1.0f;
				else acceptProbability = (float)degree / candidateDegree;

				if (logDEBUG) Logger.debug(Probe.class, "acceptProbability is " + acceptProbability);
				if (node.random.nextFloat() < acceptProbability) {
					if (logDEBUG) Logger.debug(Probe.class, "Accepted candidate.");
					//Filter for response to this probe with requested result type.
					final MessageFilter filter = createResponseFilter(type, candidate, uid, htl);
					message.set(DMT.HTL, htl);
					try {
						node.getUSM().addAsyncFilter(filter, new ResultListener(listener), this);
						if (logDEBUG) Logger.debug(Probe.class, "Sending.");
						candidate.sendAsync(message, null, this);
						return true;
					} catch (NotConnectedException e) {
						if (logMINOR) Logger.minor(Probe.class, "Peer became disconnected between check and send attempt.", e);
						// Peer no longer connected - sending was not successful. Try again.
					} catch (DisconnectedException e) {
						if (logMINOR) Logger.minor(Probe.class, "Peer became disconnected while attempting to add filter.", e);
						// Peer no longer connected - cannot send. Try again.
					}
				} else {
					/*
					 * Metropolis-Hastings correction rejected - decrement HTL so that it can run out depending on
					 * relative degrees.
					 */
					htl = probabilisticDecrement(htl);

					if (htl == 0) return false;
				}
			} else {
				if (logMINOR) Logger.minor(Probe.class, "Peer in connectedPeers was not connected.", new Exception());
			}
		}

		// Send attempt limit reached.
		if (logWARNING) {
			Logger.warning(Probe.class, "Aborting probe request: send attempt limit reached.");
		}

		listener.onError(Error.CANNOT_FORWARD, null, true);
		return true;
	}

	/**
	 * @param type probe result type requested.
	 * @param candidate node to filter for response from.
	 * @param uid probe request uid, also to be used in any result.
	 * @param htl current probe HTL; used to calculate timeout.
	 * @return filter for the requested result type, probe error, and probe refusal.
	 */
	private static MessageFilter createResponseFilter(final Type type, final PeerNode candidate, final long uid, final byte htl) {
		final int timeout = (htl - 1) * TIMEOUT_PER_HTL + TIMEOUT_HTL1;
		final MessageFilter filter = createFilter(candidate, uid, timeout);

		switch (type) {
			case BANDWIDTH: filter.setType(DMT.ProbeBandwidth); break;
			case BUILD: filter.setType(DMT.ProbeBuild); break;
			case IDENTIFIER: filter.setType(DMT.ProbeIdentifier); break;
			case LINK_LENGTHS: filter.setType(DMT.ProbeLinkLengths); break;
			case LOCATION: filter.setType(DMT.ProbeLocation); break;
			case STORE_SIZE: filter.setType(DMT.ProbeStoreSize); break;
			case UPTIME_48H:
			case UPTIME_7D: filter.setType(DMT.ProbeUptime); break;
			default: throw new UnsupportedOperationException("Missing filter for " + type.name());
		}

		//Refusal or an error should also be listened for so it can be relayed.
		filter.or(createFilter(candidate, uid, timeout).setType(DMT.ProbeRefused)
		      .or(createFilter(candidate, uid, timeout).setType(DMT.ProbeError)));

		return filter;
	}

	private static MessageFilter createFilter(final PeerNode source, final long uid, final int timeout) {
		return MessageFilter.create().setSource(source).setField(DMT.UID, uid).setTimeout(timeout);
	}

	/**
	 * Depending on node settings, sends a message to source containing either a refusal or the requested result.
	 */
	private void respond(final Type type, final Listener listener) {

		if (!respondTo(type)) {
			listener.onRefused();
			return;
		}

		/*
		 * This adds noise to the results to make information less identifiable. The goal is making it difficult
		 * to determine which value a node actually has; that any given value could mean a small range of common
		 * values. Different result types have different sigma values such that one sigma contains multiple
		 * reasonable values.
		 */
		switch (type) {
		case BANDWIDTH:
			/*
			 * 5% noise:
			 * Reasonable output bandwidth limit is 20 KiB and people are likely to set limits in increments
			 * of 1 KiB. 1 KiB / 20 KiB = 0.05 sigma.
			 * 1,024 (2^10) bytes per KiB.
			 */
			listener.onOutputBandwidth((float)randomNoise((double)node.getOutputBandwidthLimit()/(1 << 10), 0.05));
			break;
		case BUILD:
			listener.onBuild(node.nodeUpdater.getMainVersion());
			break;
		case IDENTIFIER:
			/*
			 * 5% noise:
			 * Reasonable uptime percentage is at least ~40 hours a week, or ~20%. This uptime is
			 * quantized so only something above a full percentage point (0.01 * 168 hours = 1.68 hours) of
			 * change will be guaranteed (from a percentage with a decimal component close to zero) to be
			 * reflected. 1% / 20% = 0.05 sigma.
			 *
			 * 7-day uptime with random noise, then quantized. Quantization is to make it very, very
			 * difficult to get useful information out of any given result because it is included with an
			 * identifier,
			 */
			long percent = Math.round(randomNoise(100*node.uptime.getUptimeWeek(), 0.05));
			//Clamp to byte.
			if (percent > Byte.MAX_VALUE) percent = Byte.MAX_VALUE;
			else if (percent < Byte.MIN_VALUE) percent = Byte.MIN_VALUE;
			listener.onIdentifier(probeIdentifier, (byte)percent);
			break;
		case LINK_LENGTHS:
			PeerNode[] peers = node.getConnectedPeers();
			float[] linkLengths = new float[peers.length];
			int i = 0;
			/*
			 * 1% noise:
			 * Link lengths are in the range [0.0, 0.5], and any change is enough to make the
			 * match not exact between locations. Taking as an example a link length of 0.2. and with the
			 * assumption that a change of 0.002 is enough to make it still useful for statistics but not
			 * useful for identification, 0.002 change / 0.2 link length = 0.01 sigma.
			 */
			for (PeerNode peer : peers) {
				linkLengths[i++] = (float)randomNoise(Math.min(Math.abs(peer.getLocation() - node.getLocation()),
				                                         1.0 - Math.abs(peer.getLocation() - node.getLocation())), 0.01);
			}
			listener.onLinkLengths(linkLengths);
			break;
		case LOCATION:
			listener.onLocation((float)node.getLocation());
			break;
		case STORE_SIZE:
			/*
			 * 5% noise:
			 * Reasonable datastore size is 20 GiB, and size is likely set in, at most, increments of 1 GiB.
			 * 1 GiB / 20 GiB = 0.05 sigma.
			 * 1,073,741,824 bytes (2^30) per GiB.
			 */
			listener.onStoreSize((float)randomNoise((double)node.getStoreSize()/(1 << 30), 0.05));
			break;
		case UPTIME_48H:
			/*
			 * 8% noise:
			 * Continuing with the assumption that reasonable weekly uptime is around 40 hours, this allows
			 * for 6 hours per day, 12 hours per 48 hours, or 25%. A half-hour seems a sufficient amount of
			 * ambiguity, so 0.5 hours / 48 hours ~= 1%, and 1% / 25% = 0.04 sigma.
			 */
			listener.onUptime((float)randomNoise(100*node.uptime.getUptime(), 0.04));
			break;
		case UPTIME_7D:
			/*
			 * 2.4% noise:
			 * As a 168-hour uptime covers a longer period 1 hour of ambiguity seems sufficient.
			 * 1 hour / 168 hours ~= 0.6%, and 0.6% / 20% = 0.03 sigma.
			 */
			listener.onUptime((float)randomNoise(100*node.uptime.getUptimeWeek(), 0.03));
			break;
		default:
			throw new UnsupportedOperationException("Missing response for " + type.name());
		}
	}

	private boolean respondTo(Type type) {
		switch (type){
		case BANDWIDTH: return respondBandwidth;
		case BUILD: return respondBuild;
		case IDENTIFIER: return respondIdentifier;
		case LINK_LENGTHS: return respondLinkLengths;
		case LOCATION: return respondLocation;
		case STORE_SIZE: return respondStoreSize;
		case UPTIME_48H:
		case UPTIME_7D: return respondUptime;
		default: throw new UnsupportedOperationException("Missing permissions check for " + type.name());
		}
	}

	/**
	 * Decrements 20% of the time at HTL 1; otherwise always. This is to protect the responding node, whereas the
	 * anonymity of the node which initiated the request is not a concern.
	 * @param htl current HTL
	 * @return new HTL
	 */
	private byte probabilisticDecrement(byte htl) {
		assert htl > 0;
		if (htl == 1) {
			if (node.random.nextFloat() < DECREMENT_PROBABILITY) return 0;
			return 1;
		}
		return (byte)(htl - 1);
	}

	/**
	 * Filter listener which determines the type of result and calls the appropriate probe listener method.
	 */
	private class ResultListener implements AsyncMessageFilterCallback {

		private final Listener listener;

		/**
		 * @param listener to call appropriate methods for events such as matched messages or timeout.
		 */
		public ResultListener(Listener listener) {
			this.listener = listener;
		}

		@Override
		public void onDisconnect(PeerContext context) {
			if (logDEBUG) Logger.debug(Probe.class, "Next node in chain disconnected.");
			listener.onError(Error.DISCONNECTED, null, true);
		}

		/**
		 * Parses provided message and calls appropriate Probe.Listener method for the type of result.
		 * @param message Probe result.
		 */
		@Override
		public void onMatched(Message message) {
			if(logDEBUG) Logger.debug(Probe.class, "Matched " + message.getSpec().getName());
			if (message.getSpec().equals(DMT.ProbeBandwidth)) {
				listener.onOutputBandwidth(message.getFloat(DMT.OUTPUT_BANDWIDTH_UPPER_LIMIT));
			} else if (message.getSpec().equals(DMT.ProbeBuild)) {
				listener.onBuild(message.getInt(DMT.BUILD));
			} else if (message.getSpec().equals(DMT.ProbeIdentifier)) {
				listener.onIdentifier(message.getLong(DMT.PROBE_IDENTIFIER), message.getByte(DMT.UPTIME_PERCENT));
			} else if (message.getSpec().equals(DMT.ProbeLinkLengths)) {
				listener.onLinkLengths(message.getFloatArray(DMT.LINK_LENGTHS));
			} else if (message.getSpec().equals(DMT.ProbeLocation)) {
				listener.onLocation(message.getFloat(DMT.LOCATION));
			} else if (message.getSpec().equals(DMT.ProbeStoreSize)) {
				listener.onStoreSize(message.getFloat(DMT.STORE_SIZE));
			} else if (message.getSpec().equals(DMT.ProbeUptime)) {
				listener.onUptime(message.getFloat(DMT.UPTIME_PERCENT));
			} else if (message.getSpec().equals(DMT.ProbeError)) {
				final byte rawError = message.getByte(DMT.TYPE);
				if (Error.isValid(rawError)) {
					listener.onError(Error.valueOf(rawError), null, false);
				} else {
					//Not recognized locally.
					listener.onError(Error.UNKNOWN, rawError, false);
				}
			} else if (message.getSpec().equals(DMT.ProbeRefused)) {
				listener.onRefused();
			}  else {
				throw new UnsupportedOperationException("Missing handling for " + message.getSpec().getName());
			}
		}

		@Override
		public void onRestarted(PeerContext context) {}

		@Override
		public void onTimeout() {
			if (logDEBUG) Logger.debug(Probe.class, "Timed out.");
			listener.onError(Error.TIMEOUT, null, true);
		}

		@Override
		public boolean shouldTimeout() {
			return false;
		}
	}

	/**
	 * Listener which relays responses to the node specified during construction. Used for received probe requests.
	 * This leads to reconstructing the messages, but removes potentially harmful sub-messages and also removes the
	 * need for duplicate message sending code elsewhere, If the result includes a trace this would be the place
	 * to add local results to it.
	 */
	private class ResultRelay implements Listener {

		private final PeerNode source;
		private final Long uid;

		/**
		 * @param source peer from which the request was received and to which send the response.
		 * @throws IllegalArgumentException if source is null.
		 */
		public ResultRelay(PeerNode source, Long uid) {
			this.source = source;
			this.uid = uid;
		}

		private void send(Message message) {
			if (!source.isConnected()) {
				if (logDEBUG) Logger.debug(Probe.class, SOURCE_DISCONNECT);
				return;
			}
			if (logDEBUG) Logger.debug(Probe.class, "Relaying " + message.getSpec().getName() + " back" +
			                                          " to " + source.userToString());
			try {
				source.sendAsync(message, null, Probe.this);
			} catch (NotConnectedException e) {
				if (logDEBUG) Logger.debug(Probe.class, SOURCE_DISCONNECT, e);
			}
		}

		@Override
		public void onError(Error error, Byte code, boolean local) {
			send(DMT.createProbeError(uid, error));
		}

		@Override
		public void onRefused() {
			send(DMT.createProbeRefused(uid));
		}

		@Override
		public void onOutputBandwidth(float outputBandwidth) {
			send(DMT.createProbeBandwidth(uid, outputBandwidth));
		}

		@Override
		public void onBuild(int build) {
			send(DMT.createProbeBuild(uid, build));
		}

		@Override
		public void onIdentifier(long identifier, byte uptimePercentage) {
			send(DMT.createProbeIdentifier(uid, identifier, uptimePercentage));
		}

		@Override
		public void onLinkLengths(float[] linkLengths) {
			send(DMT.createProbeLinkLengths(uid, linkLengths));
		}

		@Override
		public void onLocation(float location) {
			send(DMT.createProbeLocation(uid, location));
		}

		@Override
		public void onStoreSize(float storeSize) {
			send(DMT.createProbeStoreSize(uid, storeSize));
		}

		@Override
		public void onUptime(float uptimePercentage) {
			send(DMT.createProbeUptime(uid, uptimePercentage));
		}
	}
}
