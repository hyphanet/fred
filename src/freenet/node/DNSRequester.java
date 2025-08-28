/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * @author amphibian
 *
 * Thread that does DNS queries for unconnected peers
 */
public class DNSRequester implements Runnable {

    final Node node;
    private long lastLogTime;
    private final Set<Double> recentNodeIdentitySet = new HashSet<>();
    private final Deque<Double> recentNodeIdentityQueue = new ArrayDeque<>();
    // Set to track peers that were active during the initial startup window.
    private final Set<PeerNode> peersSeenDuringStartup = ConcurrentHashMap.newKeySet();
    // A single map to track the connection state for each peer.
    private final Map<PeerNode, PeerConnectionState> peerConnectionStates = new ConcurrentHashMap<>();
    // The base wait time after a DNS lookup.
    private static final int DNS_WAIT_MIN_MS = 1000;
    // A random delay (jitter) added to the base wait time to obscure traffic patterns.
    private static final int DNS_WAIT_JIT_MS = 60000;
    // The fast, fixed wait time for cycles that only process non-sensitive IP addresses.
    private static final int IP_WAIT_CYCLE_MS = 10000;
    // Time window (10 minutes) in which peers can attempt to make connections before needing to cooldown.
    private static final long ATTEMPT_WINDOW_MS = 10 * 60 * 1000;
    // Initial cooldown (5 minutes) for a peer after making several failed connection attempts. This is the base duration for the exponential backoff mechanism.
    private static final long INITIAL_COOLDOWN_MS = 5 * 60 * 1000;
    // Maximum cooldown period (1 hour) for the exponential backoff. This prevents a peer from being blocked for an unreasonably long time.
    private static final long MAX_COOLDOWN_MS = 1 * 60 * 60 * 1000;
    // Prevents long overflow in backoff calculation
    private static final int MAX_BACKOFF_EXPONENT = 30;
    // The percentage of other hostname peers to try before re-trying a recently queried one.
    private static final double HOSTNAME_RECENCY_QUEUE_PERCENTAGE = 0.81;
    // Periodic time (every 6 hours) that runs a cleanup task to keep our maps free of stale peers
    private static final long CLEANUP_INTERVAL_MS = 6 * 60 * 60 * 1000;
    // Log at most once per second
    private static final long LOG_THROTTLE_MS = 1000;
    private long lastCleanupTime = System.currentTimeMillis();
    private boolean startupPhaseCompleted = false;
    // Only set when doing simulations.
    static boolean DISABLE = false;

    private static volatile boolean logMINOR;
    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
            }
        });
    }

    DNSRequester(Node node) {
        this.node = node;
    }

    void start() {
        Logger.normal(this, "Starting DNSRequester");
        node.getExecutor().execute(this, "DNSRequester thread for "+node.getDarknetPortNumber());
    }

    @Override
    public void run() {
        while(true) {
            try {
                realRun();
            } catch (Throwable t) {
                Logger.error(this, "Caught in DNSRequester: "+t, t);
            }
        }
    }

    private void realRun() {
        long now = System.currentTimeMillis();

        // Get all peers and partition them
        PeerNode[] allPeers = node.getPeers().myPeers();
        cleanupStalePeers(now, allPeers);
        PartitionedPeers partitioned = partitionPeers(allPeers);

        if (logMINOR) {
            if ((now - lastLogTime) > LOG_THROTTLE_MS) {
                Logger.minor(this, "Processing Requests: " + (partitioned.hostnamePeers.size() + partitioned.ipPeers.size()) + " unconnected peers.");
                lastLogTime = now;
            }
        }

        boolean isStartupPhase = (now < node.getStartupTime() + ATTEMPT_WINDOW_MS);

        // ===== PHASE A: IP peers (safe to be fast) =====
        processIpOnlyPeers(now, partitioned.ipPeers, isStartupPhase);

        // ===== PHASE B: Hostname peers (stealth path to protect anonymity) =====
        boolean touchedHostname = processHostnamePeers(now, partitioned.hostnamePeers, isStartupPhase);

        // If the startup phase just ended, clear the startup-only set to release memory.
        if (!isStartupPhase && !startupPhaseCompleted) {
            startupPhaseCompleted = true; // Mark as completed so we only do this once.
            if(logMINOR) {
                Logger.minor(this, "Startup phase complete. Clearing " + peersSeenDuringStartup.size() + " startup peer references.");
            }
            peersSeenDuringStartup.clear();
        }

        // ===== SLEEP TIME =====
        int nextMaxWaitTime = determineNextWaitTime(touchedHostname);

        try {
            synchronized (this) {
                wait(nextMaxWaitTime);
            }
        } catch (InterruptedException e) {
            // Ignore, just wake up. Just sleeping to not busy wait anyway
        }
    }

    /**
     * The state-tracking maps are cleaned up when a peer connects. However, if a peer is permanently
     * removed from the network (i.e., it no longer appears in node.getPeers().myPeers()), its entry
     * might persist in these maps indefinitely, leading to a slow memory leak. This helps keep maps clean.
     * @param now The current time in milliseconds.
     * @param allPeers The list of all unconnected peers (both IP and hostname).
     */
    private void cleanupStalePeers(long now, PeerNode[] allPeers) {
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            if (logMINOR) {
                Logger.minor(this, "Running periodic cleanup of stale peer state...");
            }

            // Keep only unconnected peers
            Set<PeerNode> activePeers = new HashSet<>(allPeers.length);
            for (PeerNode peer : allPeers) {
                if (!peer.isConnected()) {
                    activePeers.add(peer);
                }
            }

            // Efficiently remove any keys (peers) from our state map that are no longer active.
            peerConnectionStates.keySet().retainAll(activePeers);

            lastCleanupTime = now;
            if (logMINOR) {
                Logger.minor(this, "Stale peer cleanup complete.");
            }
        }
    }

    /**
     * Partitions peers into IP-only and hostname-required lists.
     * Also cleans up state for peers that have successfully connected.
     * @param allPeers An array of all current peers.
     * @return A PartitionedPeers object containing the two lists.
     */
    private PartitionedPeers partitionPeers(PeerNode[] allPeers) {
        List<PeerNode> ipPeers = new ArrayList<>();
        List<PeerNode> hostnamePeers = new ArrayList<>();

        for (PeerNode peer : allPeers) {
            if (peer.isConnected()) {
                // This peer is now connected. Clean up any lingering state from when it was unconnected.
                peerConnectionStates.remove(peer);
            } else {
                // Unconnected: categorize for processing.
                if (peer.getPeer().getFreenetAddress().hasHostname()) {
                    hostnamePeers.add(peer);
                } else {
                    ipPeers.add(peer);
                }
            }
        }
        return new PartitionedPeers(ipPeers, hostnamePeers);
    }

    /**
     * Manages connection attempts for IP-only peers. This method iterates through all
     * IP peers and applies the shared backoff and attempt logic to each one.
     * @param now The current time in milliseconds.
     * @param ipPeers The list of unconnected IP-only peers.
     * @param isStartupPhase True if the node is in its initial startup window.
     */
    private void processIpOnlyPeers(long now, List<PeerNode> ipPeers, boolean isStartupPhase) {
        for (PeerNode pn : ipPeers) {
            managePeerAttempt(pn, now, true, isStartupPhase);
        }
    }

    /**
     * Manages DNS lookups for peers with hostnames. It uses a randomized, rate-limited approach
     * to protect anonymity and applies the shared backoff logic to the selected candidate.
     * @param now The current time in milliseconds.
     * @param hostnamePeers The list of unconnected peers with hostnames.
     * @param isStartupPhase True if the node is in its initial startup window.
     * @return True if a DNS lookup was attempted, false otherwise.
     */
    private boolean processHostnamePeers(long now, List<PeerNode> hostnamePeers, boolean isStartupPhase) {
        PeerNode[] hostnameCandidates = hostnamePeers.stream()
            // identify recent nodes by location, because the exact location cannot be used twice
            // (that already triggers the simplest pitch black attack defenses)
            // So, this filters out peers whose location has been recently selected to ensure variety.
            // Note: Double may not be comparable in general (floating point),
            // but just checking for equality with itself is safe
            .filter(peerNode -> !recentNodeIdentitySet.contains(peerNode.getLocation()))
            .toArray(PeerNode[]::new);

        if (hostnameCandidates.length > 0) {
            // check a randomly chosen node that has not been checked
            // recently to avoid sending bursts of DNS requests
            PeerNode pn = hostnameCandidates[node.getFastWeakRandom().nextInt(hostnameCandidates.length)];

            // 1. Add the selected peer to the recency set immediately.
            // This ensures we don't pick it again right away, even if it's in cooldown.

            // Do not request this node again, until at least 81% (HOSTNAME_RECENCY_QUEUE_PERCENTAGE)
            // of the other unconnected nodes have been checked
            recentNodeIdentitySet.add(pn.getLocation());
            recentNodeIdentityQueue.offerFirst(pn.getLocation());
            final int removalThreshold = (int) Math.ceil(HOSTNAME_RECENCY_QUEUE_PERCENTAGE * hostnameCandidates.length);
            while (recentNodeIdentityQueue.size() > removalThreshold) {
                recentNodeIdentitySet.remove(recentNodeIdentityQueue.removeLast());
            }

            // 2. Now, manage the attempt. Apply the unified backoff logic. This will return false if the peer is in cooldown.
            boolean attemptMade = managePeerAttempt(pn, now, false, isStartupPhase);
            return attemptMade; // The return value should still depend on whether an actual attempt was made.
        } else {
            recentNodeIdentitySet.clear();
            recentNodeIdentityQueue.clear();
        }
        return false; // No hostname peer was processed.
    }

    /**
     * Centralized method to manage connection/lookup attempts for any peer.
     * It handles cooldowns, startup phase, attempt windows, and exponential backoff.
     * @param pn The PeerNode to process.
     * @param now The current time in milliseconds.
     * @param isIpOnly True if this is an IP-only peer (for maybeUpdateHandshakeIPs).
     * @param isStartupPhase True if the node is in its initial startup window.
     * @return True if a connection/lookup attempt was made, false if the peer was skipped (e.g., in cooldown).
     */
    private boolean managePeerAttempt(PeerNode pn, long now, boolean isIpOnly, boolean isStartupPhase) {
        // 1. During the initial startup phase, try all peers. State is not tracked.
        if (isStartupPhase) {
            peersSeenDuringStartup.add(pn); // Record that we saw this peer during startup.
            pn.maybeUpdateHandshakeIPs(isIpOnly);
            return true;
        }

        // 2. After startup, manage the connect/cooldown cycle with backoff.
        PeerConnectionState currentState = peerConnectionStates.get(pn);

        // Case A: No prior state for this peer.
        if (currentState == null) {
            // New peers get a window starting now. Peers seen during startup get a window starting then.
            boolean seenDuringStartup = peersSeenDuringStartup.contains(pn);
            long attemptStart = seenDuringStartup ? node.getStartupTime() : now;

            currentState = new PeerConnectionState(attemptStart);
            peerConnectionStates.put(pn, currentState);

            if (!seenDuringStartup) {
                // This is a brand-new peer that appeared after startup. Give it a fresh window.
                pn.maybeUpdateHandshakeIPs(isIpOnly);
                return true; // Allow this new peer its full window before checking for cooldown.
            }
        }

        // Case B: Peer has existing state. Check for cooldown.
        if (currentState.isCoolingDown(now)) {
            return false; // Still in cooldown, skip.
        }

        // Case C: Peer is not cooling down. If connectAttemptStarted is null, the cooldown just expired.
        if (currentState.connectAttemptStarted == null) {
            // Cooldown has ended. Start a new attempt window.
            PeerConnectionState newState = currentState.startNewAttemptWindow(now);
            peerConnectionStates.put(pn, newState);
            pn.maybeUpdateHandshakeIPs(isIpOnly);
            return true;
        }

        // Case D: Peer is in an active attempt window. Check if it has expired.
        if (now < currentState.connectAttemptStarted + ATTEMPT_WINDOW_MS) {
            // Window is still open, so we can try to connect/resolve.
            pn.maybeUpdateHandshakeIPs(isIpOnly);
            return true;
        } else {
            // Window has expired and the peer is still not connected. Time for backoff.
            PeerConnectionState newState = currentState.enterCooldown(now);
            peerConnectionStates.put(pn, newState);

            if (logMINOR) {
                String peerType = isIpOnly ? "IP" : "Hostname";
                long cooldownDuration = newState.cooldownUntil - now;
                Logger.minor(this, peerType + " Peer " + pn + " failed to connect/resolve. Applying backoff cooldown for "
                        + (cooldownDuration / 60000) + " minutes. Failure count: " + newState.failureCount);
            }
            return false; // An attempt was NOT made; the peer was put into backoff instead.
        }
    }

    /**
     * Determines the appropriate wait time for the next cycle.
     * @param touchedHostname True if a hostname peer was processed in this cycle.
     * @return The calculated wait time in milliseconds.
     */
    private int determineNextWaitTime(boolean touchedHostname) {
        // Pick the stricter (slower) sleep if we touched a hostname this loop; otherwise use the fast IP cadence.
        // Heuristic: if we attempted any hostname peer this loop, use DNS range; else IP range.
        if (touchedHostname) {
            // when a hostname lookup was scheduled
            return DNS_WAIT_MIN_MS + node.getFastWeakRandom().nextInt(DNS_WAIT_JIT_MS);
        } else {
            // when ONLY pure IPs were processed this loop
            return IP_WAIT_CYCLE_MS;
        }
    }

    public void forceRun() {
        synchronized(this) {
            notifyAll();
        }
    }

    /**
     * A simple container to hold the result of partitioning peers.
     */
    private static class PartitionedPeers {
        final List<PeerNode> ipPeers;
        final List<PeerNode> hostnamePeers;

        PartitionedPeers(List<PeerNode> ipPeers, List<PeerNode> hostnamePeers) {
            this.ipPeers = ipPeers;
            this.hostnamePeers = hostnamePeers;
        }
    }

    /**
     * Encapsulates the connection attempt state for a single peer, including
     * cooldown timers, attempt windows, and failure counts for exponential backoff.
     * This class is immutable to ensure safer concurrency pattern; state transitions
     * create new instances.
     */
    private static class PeerConnectionState {
        final Long cooldownUntil;
        final Long connectAttemptStarted;
        final int failureCount;

        /** Constructor for a new attempt window. */
        PeerConnectionState(long connectAttemptStarted) {
            this(null, connectAttemptStarted, 0);
        }

        /** Private constructor for internal state transitions. */
        private PeerConnectionState(Long cooldownUntil, Long connectAttemptStarted, int failureCount) {
            this.cooldownUntil = cooldownUntil;
            this.connectAttemptStarted = connectAttemptStarted;
            this.failureCount = failureCount;
        }

        /** Checks if the peer is currently in a cooldown period. */
        boolean isCoolingDown(long now) {
            return cooldownUntil != null && now < cooldownUntil;
        }

        /** Returns a new state representing the peer entering a cooldown period after a failure. */
        PeerConnectionState enterCooldown(long now) {
            int newFailureCount = this.failureCount + 1;
            // Calculate backoff time: initial_cooldown * 2^(failures - 1)
            // We cap the result at MAX_COOLDOWN_MS.
            // Use bit-shift for 2^n, capped to prevent overflow (efficient power-of-2 calculation)
            long backoffMultiplier = 1L << Math.min(newFailureCount - 1, MAX_BACKOFF_EXPONENT);
            long cooldownDuration = Math.min(INITIAL_COOLDOWN_MS * backoffMultiplier, MAX_COOLDOWN_MS);
            long newCooldownUntil = now + cooldownDuration;
            // When entering cooldown, the attempt window is over. Nullify connectAttemptStarted.
            return new PeerConnectionState(newCooldownUntil, null, newFailureCount);
        }

        /** Returns a new state for a peer whose cooldown has expired, starting a new attempt window. */
        PeerConnectionState startNewAttemptWindow(long now) {
            // Start a new window with the current failure count, which will reset on next success.
            return new PeerConnectionState(null, now, this.failureCount);
        }
    }
}
