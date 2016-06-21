package freenet.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

}
