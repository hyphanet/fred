package freenet.node;

import java.util.Arrays;
import java.util.Set;

import freenet.support.Logger;

public class PeerLocation {
	
	/** Current location in the keyspace, or -1 if it is unknown */
	private double currentLocation;
	/** Current sorted array of locations of our peer's peers. Must not be modified,
	 may only be replaced entirely. */
	private double[] currentPeersLocation;
	/** Time the location was set */
	private long locSetTime;

	PeerLocation(String locationString) {
		currentLocation = Location.getLocation(locationString);
		locSetTime = System.currentTimeMillis();
	}
	
	public synchronized String toString() {
		return Double.toString(currentLocation);
	}

	/** Should only be called in the constructor */
	public void setPeerLocations(String[] peerLocationsString) {
		if(peerLocationsString != null) {
			double[] peerLocations = new double[peerLocationsString.length];
			for(int i = 0; i < peerLocationsString.length; i++)
				peerLocations[i] = Location.getLocation(peerLocationsString[i]);
			updateLocation(currentLocation, peerLocations);
		}
	}

	public synchronized double getLocation() {
		return currentLocation;
	}

	/** Returns an array copy of locations of our peer's peers, or null if we don't have them. */
	public synchronized double[] getPeersLocationArray() {
		if (currentPeersLocation == null) {
			return null;
		}
		return Arrays.copyOf(currentPeersLocation, currentPeersLocation.length);
	}

	public synchronized long getLocationSetTime() {
		return locSetTime;
	}

	public synchronized boolean isValidLocation() {
		return Location.isValid(currentLocation);
	}

	public synchronized int getDegree() {
		if (currentPeersLocation == null) return 0;
		return currentPeersLocation.length;
	}

	boolean updateLocation(double newLoc, double[] newLocs) {
		if (!Location.isValid(newLoc)) {
			Logger.error(this, "Invalid location update for " + this+ " ("+newLoc+')', new Exception("error"));
			// Ignore it
			return false;
		}

		final double[] newPeersLocation = new double[newLocs.length];
		for (int i = 0; i < newLocs.length; i++) {
			final double loc = newLocs[i];
			if (!Location.isValid(loc)) {
				Logger.error(this, "Invalid location update for " + this + " (" + loc + ")", new Exception("error"));
				// Ignore it
				return false;
			}
			newPeersLocation[i] = loc;
		}
		
		Arrays.sort(newPeersLocation);
		boolean anythingChanged = false;

		synchronized (this) {
			if (!Location.equals(currentLocation, newLoc) || currentPeersLocation == null) {
				anythingChanged = true;
			}
			if (!anythingChanged) {
				anythingChanged = currentPeersLocation.length != newPeersLocation.length;
			}
			if (!anythingChanged) {
				for (int i = 0; i < currentPeersLocation.length; i++) {
					if (!Location.equals(currentPeersLocation[i], newPeersLocation[i])) {
						anythingChanged = true;
						break;
					}
				}
			}
			currentLocation = newLoc;
			currentPeersLocation = newPeersLocation;
			locSetTime = System.currentTimeMillis();
		}
		return anythingChanged;
	}

	synchronized double setLocation(double newLoc) {
		double oldLoc = currentLocation;
		if(!Location.equals(newLoc, currentLocation)) {
			currentLocation = newLoc;
			locSetTime = System.currentTimeMillis();
		}
		return oldLoc;
	}

	/**
	 * Finds the position of the first element in the sorted list greater than the given element,
	 * or -1 if none.
	 * This is a binary search of logarithmic complexity.
	 */
	private static int findFirstGreater(final double[] elems, final double x) {
		int low = 0;
		int high = elems.length;
		int mid;
		if (elems[high - 1] <= x) {
			return -1;
		}
		while (low != high) {
			mid = low + (high - low) / 2;
			if (elems[mid] > x) {
				high = mid;
			} else {
				low = mid + 1;
			}
		}
		return low;
	}

	/**
	 * Finds the position of the closest location in the sorted list of locations.
	 * This is a binary search of logarithmic complexity.
	 */
	static int findClosestLocation(final double[] locs, final double l) {
		assert(locs.length > 0);
		if (locs.length == 1) {
			return 0;
		}
		final int firstGreater = findFirstGreater(locs, l);
		final int left;
		final int right;
		if (firstGreater == -1 || firstGreater == 0) {
			// All locations are greater, or all locations are smaller.
			// Closest location must be either smallest or greatest location.
			left = locs.length - 1;
			right = 0;
		} else {
			left = firstGreater - 1;
			right = firstGreater;
		}
		if (Location.distance(l, locs[left]) <= Location.distance(l, locs[right])) {
			return left;
		}
		return right;
	}

	/**
	 * Finds the closest non-excluded peer in O(log n + m) time, where n is the number of peers and
	 * m the number of exclusions.
	 * @param exclude the set of locations to exclude, may be null
	 * @return the closest non-excluded peer's location, or NaN if none is found
	 */
	public double getClosestPeerLocation(final double l, Set<Double> exclude) {
		final double[] locs;
		synchronized (this) {
			locs = currentPeersLocation;
		}
		if (locs == null || locs.length == 0) {
			return Double.NaN;
		}
		final int closest = findClosestLocation(locs, l);
		if (exclude == null || !exclude.contains(locs[closest])) {
			return locs[closest];
		}

		int left = (closest == 0) ? (locs.length - 1) : (closest - 1);
		int right = (closest == locs.length - 1) ? 0 : (closest + 1);
		double leftDist = Location.distance(l, locs[left]);
		double rightDist = Location.distance(l, locs[right]);
		// Iterate over at most m closest peers
		while (left != right) {
			if (leftDist <= rightDist) {
				final double loc = locs[left];
				if (!exclude.contains(loc)) {
					return loc;
				}
				left = (left == 0) ? (locs.length - 1) : (left - 1);
				leftDist = Location.distance(l, locs[left]);
			} else {
				final double loc = locs[right];
				if (!exclude.contains(loc)) {
					return loc;
				}
				right = (right == locs.length - 1) ? 0 : (right + 1);
				rightDist = Location.distance(l, locs[right]);
			}
		}
		final double loc = locs[left];
		if (!exclude.contains(loc)) {
			return loc;
		}
		return Double.NaN;
	}
}
