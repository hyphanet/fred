package freenet.node;

import java.util.Arrays;

import freenet.support.Logger;

public class PeerLocation {
	
	/** Current location in the keyspace, or -1 if it is unknown */
	private Location currentLocation;
	/** Current locations of our peer's peers */
	private Location.Valid[] currentPeersLocation;
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
	public void setPeerLocations(String[] peerLocationsString) throws InvalidLocationException {
		if(peerLocationsString != null) {
			Location.Valid[] peerLocations = new Location.Valid[peerLocationsString.length];
			for(int i = 0; i < peerLocationsString.length; i++)
				peerLocations[i] = Location.fromString(peerLocationsString[i]).validated();
			synchronized(this) {
				currentPeersLocation = peerLocations;
			}
		}
	}

	public synchronized Location getLocation() {
		return currentLocation;
	}

	synchronized Location.Valid[] getPeerLocations() {
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
	    try {
	        Location.Valid validNewLoc = newLoc.validated();
	        Location.Valid[] validNewLocs = Location.validated(newLocs);
	        
		    Arrays.sort(validNewLocs);
		
		    boolean anythingChanged = false;

		    synchronized(this) {
			    if(!currentLocation.equals(validNewLoc))
				    anythingChanged = true;
			    currentLocation = validNewLoc;
			    if(currentPeersLocation == null)
				    anythingChanged = true;
			    else if(currentPeersLocation != null && !anythingChanged) {
				    if(currentPeersLocation.length != validNewLocs.length)
					    anythingChanged = true;
				    else {
					    for(int i=0;i<currentPeersLocation.length;i++) {
						    if(!currentPeersLocation[i].equals(validNewLocs[i])) {
							    anythingChanged = true;
							    break;
						    }
					    }
				    }
			    }
			    currentPeersLocation = validNewLocs;
			    locSetTime = System.currentTimeMillis();
		    }
		    return anythingChanged;
	    }
	    catch (InvalidLocationException e) {
	        Logger.error(this, "Invalid location update for " + this, e);
	        // Ignore.
	        return false;
	    }
	}

	synchronized Location setLocation(Location.Valid newLoc) {
		Location oldLoc = currentLocation;
		if(!newLoc.equals(currentLocation)) {
			currentLocation = newLoc;
			locSetTime = System.currentTimeMillis();
		}
		return oldLoc;
	}

}
