package freenet.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import freenet.support.Logger;

public class PeerLocation {
	
	/** Current location in the keyspace, or -1 if it is unknown */
	private double currentLocation;
	/** Current sorted unmodifiable list of locations of our peer's peers */
	private List<Double> currentPeersLocation;
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

	synchronized List<Double> getPeerLocations() {
		return currentPeersLocation;
	}

	public synchronized long getLocationSetTime() {
		return locSetTime;
	}

	public synchronized boolean isValidLocation() {
		return Location.isValid(currentLocation);
	}

	public synchronized int getDegree() {
		if (currentPeersLocation == null) return 0;
		return currentPeersLocation.size();
	}

	boolean updateLocation(double newLoc, double[] newLocs) {
		if (!Location.isValid(newLoc)) {
			Logger.error(this, "Invalid location update for " + this+ " ("+newLoc+')', new Exception("error"));
			// Ignore it
			return false;
		}

		ArrayList<Double> newPeersLocation = new ArrayList<Double>(newLocs.length);
		for (double loc : newLocs) {
			if (!Location.isValid(loc)) {
				Logger.error(this, "Invalid location update for " + this + " (" + loc + ")", new Exception("error"));
				// Ignore it
				return false;
			}
			newPeersLocation.add(loc);
		}
		
		Collections.sort(newPeersLocation);
		boolean anythingChanged = false;

		synchronized (this) {
			if (!Location.equals(currentLocation, newLoc) || currentPeersLocation == null) {
				anythingChanged = true;
			}
			if (!anythingChanged) {
				anythingChanged = currentPeersLocation.size() != newPeersLocation.size();
			}
			if (!anythingChanged) {
				for (int i = 0; i < currentPeersLocation.size(); i++) {
					if (!Location.equals(currentPeersLocation.get(i), newPeersLocation.get(i))) {
						anythingChanged = true;
						break;
					}
				}
			}
			currentLocation = newLoc;
			currentPeersLocation = Collections.unmodifiableList(newPeersLocation);
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
	private static int findFirstGreater(List<Double> elems, double x) {
		int low = 0;
		int high = elems.size();
		int mid;
		if (elems.get(high - 1) <= x) {
			return -1;
		}
		while (low != high) {
			mid = low + (high - low) / 2;
			if (elems.get(mid) > x) {
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
	static int findClosestLocation(List<Double> locs, double l) {
		assert(locs.size() > 0);
		if (locs.size() == 1) {
			return 0;
		}
		final int firstGreater = findFirstGreater(locs, l);
		final int left;
		final int right;
		if (firstGreater == -1 || firstGreater == 0) {
			// All locations are greater, or all locations are smaller.
			// Closest location must be either smallest or greatest location.
			left = locs.size() - 1;
			right = 0;
		} else {
			left = firstGreater - 1;
			right = firstGreater;
		}
		if (Location.distance(l, locs.get(left)) <= Location.distance(l, locs.get(right))) {
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
	public double getClosestPeerLocation(double l, Set<Double> exclude) {
		List<Double> locs = getPeerLocations();
		final int N = locs.size();
		if (N == 0) {
			return Double.NaN;
		}
		final int closest = findClosestLocation(locs, l);
		if (exclude == null || !exclude.contains(closest)) {
			return locs.get(closest);
		}

		int left = (closest - 1) % N;
		int right = (closest + 1) % N;
		double leftDist = Location.distance(l, locs.get(left));
		double rightDist = Location.distance(l, locs.get(right));
		// Iterate over at most m closest peers
		while (left != right) {
			if (leftDist <= rightDist) {
				final double loc = locs.get(left);
				if (!exclude.contains(loc)) {
					return loc;
				}
				left = (left - 1) % N;
				leftDist = Location.distance(l, locs.get(left));
			} else {
				final double loc = locs.get(right);
				if (!exclude.contains(loc)) {
					return loc;
				}
				right = (right + 1) % N;
				rightDist = Location.distance(l, locs.get(right));
			}
		}
		final double loc = locs.get(left);
		if (!exclude.contains(loc)) {
			return loc;
		}
		return Double.NaN;
	}
}
