package freenet.node;

import java.util.Arrays;

import freenet.support.Logger;

public class PeerLocation {
	
	/** Current location in the keyspace, or -1 if it is unknown */
	private double currentLocation;
	/** Current locations of our peer's peers */
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
			synchronized(this) {
				currentPeersLocation = peerLocations;
			}
		}
	}

	public synchronized double getLocation() {
		return currentLocation;
	}

	synchronized double[] getPeerLocations() {
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
		return currentPeersLocation.length;
	}

	boolean updateLocation(double newLoc, double[] newLocs) {
		if(!Location.isValid(newLoc)) {
			Logger.error(this, "Invalid location update for " + this+ " ("+newLoc+')', new Exception("error"));
			// Ignore it
			return false;
		}

		for(double currentLoc : newLocs) {
			if(!Location.isValid(currentLoc)) {
				Logger.error(this, "Invalid location update for " + this + " ("+currentLoc+')', new Exception("error"));
				// Ignore it
				return false;
			}
		}

		Arrays.sort(newLocs);
		
		boolean anythingChanged = false;

		synchronized(this) {
			if(!Location.equals(currentLocation, newLoc))
				anythingChanged = true;
			currentLocation = newLoc;
			if(currentPeersLocation == null)
				anythingChanged = true;
			else if(currentPeersLocation != null && !anythingChanged) {
				if(currentPeersLocation.length != newLocs.length)
					anythingChanged = true;
				else {
					for(int i=0;i<currentPeersLocation.length;i++) {
						if(!Location.equals(currentPeersLocation[i], newLocs[i])) {
							anythingChanged = true;
							break;
						}
					}
				}
			}
			currentPeersLocation = newLocs;
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
