package freenet.node;

import java.util.Arrays;

import freenet.support.Logger;

public class PeerLocation {
	
	/** Current location in the keyspace, or -1 if it is unknown */
	private Location currentLocation;
	/** Current locations of our peer's peers */
	private Location[] currentPeersLocation;
	/** Time the location was set */
	private long locSetTime;

	PeerLocation(String locationString) {
		currentLocation = Location.fromString(locationString);
		locSetTime = System.currentTimeMillis();
	}
	
	public synchronized String toString() {
		return currentLocation.toString();
	}

	/** Should only be called in the constructor */
	public void setPeerLocations(String[] peerLocationsString) {
		if(peerLocationsString != null) {
			Location[] peerLocations = new Location[peerLocationsString.length];
			for(int i = 0; i < peerLocationsString.length; i++)
				peerLocations[i] = Location.fromString(peerLocationsString[i]);
			synchronized(this) {
				currentPeersLocation = peerLocations;
			}
		}
	}

	public synchronized Location getLocation() {
		return currentLocation;
	}

	synchronized Location[] getPeerLocations() {
		return currentPeersLocation;
	}

	public synchronized long getLocationSetTime() {
		return locSetTime;
	}

	public synchronized boolean isValidLocation() {
		return currentLocation.isValid();
	}

	public synchronized int getDegree() {
		if (currentPeersLocation == null) return 0;
		return currentPeersLocation.length;
	}

	boolean updateLocation(Location newLoc, Location[] newLocs) {
		if(!newLoc.isValid()) {
			Logger.error(this, "Invalid location update for " + this+ " ("+newLoc+')', new Exception("error"));
			// Ignore it
			return false;
		}

		for(Location currentLoc : newLocs) {
			if(!currentLoc.isValid()) {
				Logger.error(this, "Invalid location update for " + this + " ("+currentLoc+')', new Exception("error"));
				// Ignore it
				return false;
			}
		}

		Arrays.sort(newLocs);
		
		boolean anythingChanged = false;

		synchronized(this) {
			if(!currentLocation.equals(newLoc))
				anythingChanged = true;
			currentLocation = newLoc;
			if(currentPeersLocation == null)
				anythingChanged = true;
			else if(currentPeersLocation != null && !anythingChanged) {
				if(currentPeersLocation.length != newLocs.length)
					anythingChanged = true;
				else {
					for(int i=0;i<currentPeersLocation.length;i++) {
						if(!currentPeersLocation[i].equals(newLocs[i])) {
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

	synchronized Location setLocation(Location newLoc) {
		Location oldLoc = currentLocation;
		if(!newLoc.equals(currentLocation)) {
			currentLocation = newLoc;
			locSetTime = System.currentTimeMillis();
		}
		return oldLoc;
	}

}
