package freenet.model;

import java.util.List;
import java.util.ArrayList;
import java.util.ListIterator;

import java.text.DecimalFormat;

/**
 * Computes (what might be) the ideal locations for our peers given the number
 * of peers that we wish. Due to the algorithim, it is probably not accurate
 * for small numbers.
 *
 * Theory: if we have only one peer, it must be "right on top of us".
 * Theory: as we add peers, we want more close peers than far.
 *
 * Implemented as a "circular" list of xenophobic peers which want to
 * distance themselves from their neighbors by decreasing amounts. The
 * node which is "us" is considered to be on either side of the list.
 *
 * This entire class could probably be replaced with a sigmoid function.
 *
 * Created: Sat Oct 23 14:14:08 2010
 *
 * @author <a href="mailto:robert@freenetproject.org"></a>
 * @version 1.0
 */
public final class SmallWorldLinkModel {

    /**
     * Creates a new <code>SmallWorldLinkModel</code> instance.
     *
     */
    public SmallWorldLinkModel(int numPeers) {
	if (numPeers<1)
	    throw new IllegalArgumentException("numPeers must be positive");
	this.numPeers=numPeers;
	//bad form :(, so made class final :(
	generateList();
	setIdealLocations();
    }

    private final int numPeers;

    double offset=0.0;

    /**
     * Dynamically sets the origin point for the model. Effects all future
     * slot evaluations, and "ideal location" reports.
     * e.g. if we are at 0.25, then 'far' peers will be at 0.75.
     */
    public void setFreenetLocation(double location) {
	this.offset=location;
    }

    double listedFear=0.0;

    Slot closestPeer=new Slot(1024.0);

    //flip/flop between adding to front & back of list.
    boolean lastWasFront;

    List<Slot> list=new ArrayList<Slot>();

    private void generateList() {
	int peers=1;
	while (peers<numPeers) {
	    Slot pushMe=closestPeer;
	    closestPeer=new Slot(closestPeer.phobia);
	    listedFear+=pushMe.phobia;
	    if (lastWasFront) {
		list.add(pushMe);
		lastWasFront=false;
		//every-other-time we reduce the phobia to make it 'balanced'
		closestPeer.phobia*=SQUISH_FACTOR;
	    } else {
		list.add(0, pushMe);
		lastWasFront=true;
	    }
	    peers++;
	}
    }

    //Acceptable range (0.0, 1.0], related to "cluster coeffecient"
    // - at  0.5 we might have 2-3 peers on the "far side" of the network.
    // - at  1.0 peers will be evenly distributed
    // - at >1.0 we will have "mostly far peers" (!!! dont)
    private static final double SQUISH_FACTOR=0.5;

    private void setIdealLocations() {
	double totalFear=listedFear+closestPeer.phobia;
	double location=0.0;

	for (Slot slot : list) {
	    location+=slot.phobia/totalFear;
	    slot.location=location;
	}

	location+=closestPeer.phobia/totalFear;
	//NB: should always be 1.0
	closestPeer.location=location;
    }

    public void debug() {
	DecimalFormat decimalFormat=new DecimalFormat("#.####");
	System.out.println("*offset = "+decimalFormat.format(offset));

	for (Slot slot : list) {
	    System.out.println(decimalFormat.format(wrapHigh(slot.location+offset))+"\t"+decimalFormat.format(slot.phobia));
	}

	System.out.println(decimalFormat.format(wrapHigh(closestPeer.location+offset))+"\t"+decimalFormat.format(closestPeer.phobia));
    }

    public void clearSlots() {
	for (Slot slot : list) {
	    slot.peerImpl=null;
	}
	closestPeer.peerImpl=null;
    }

    /**
     * Returns the "change in location" which would best
     * satisfy the current model with the given peers.
     * e.g. if all our peers are 0.2 off-ideal, it will return 0.2...
     */
    public double getAvgLinkDeviation() {
	int count=0;
	double deviation=0.0;
	for (Slot slot : list) {
	    if (slot.peerImpl!=null) {
		count++;
		/*
		  NB: 'slot.location' is within 0-1, and we know that
		  (peerLocation-offset) is within the 'slot' (which does
		  not wrap over the 1.0 line).
		*/
		deviation+=slot.location-(slot.absolutePeerLocation-offset);
	    }
	}
	Slot slot=closestPeer;
	if (slot.peerImpl!=null) {
	    count++;
	    /*
	      NB: 'slot.location' is within 0-1, and we know that
	      (peerLocation-offset) is within the 'slot' (which does
	      not wrap over the 1.0 line).
	    */
	    deviation+=slot.location-(slot.absolutePeerLocation-offset);
	}
	return deviation/count;
    }

    public boolean wouldFillEmptySlot(double location) {
	return fillSlot(location, null);
    }

    /**
     * Feeds the model a peer to hold in a given slot. Returns true
     * if the slot was not yet filled. Has no effect if slot was
     * already occupied.
     */
    public synchronized boolean fillSlot(double location, Object impl) {
	location-=offset;
	if (location<0.0)
	    location+=1.0;
	double lastLocation=0.0;
	double nextLocation;
	//For this initial implementation, slot "boundaries" are
	//reasoned to be halfway between either node's location.
	ListIterator<Slot> i=list.listIterator();
	while (i.hasNext()) {
	    Slot slot=i.next();
	    Slot nextSlot;
	    int nextIndex=i.nextIndex();
	    if (nextIndex==list.size()) {
		nextSlot=closestPeer;
	    } else {
		nextSlot=list.get(nextIndex);
	    }
	    nextLocation=nextSlot.location;
	    //@bug: is underflow possible here? w/ how many peers?
	    double leftBoundary=(lastLocation+slot.location)/2;
	    double rightBoundary=(slot.location+nextLocation)/2;
	    if (leftBoundary < location && location < rightBoundary) {
		//This is our slot!
		if (slot.peerImpl!=null) {
		    return false;
		} else {
		    slot.peerImpl=impl;
		    slot.absolutePeerLocation=location;
		    return true;
		}
	    } 
	    lastLocation=slot.location;
	}
	nextLocation=1.0;
	Slot slot=closestPeer;
	//sanity check
	{
	    double leftBoundary=(lastLocation+slot.location)/2;
	    double rightBoundary=(slot.location+nextLocation)/2;
	    if (leftBoundary <= location && location <= rightBoundary) {
		//This is our slot!
	    } else {
		throw new RuntimeException("could not find slot for: "+location+", expecting normalized double (0.0-1.0)");
	    }
	}
	if (slot.peerImpl!=null) {
	    return false;
	} else {
	    slot.peerImpl=impl;
	    slot.absolutePeerLocation=location;
	    return true;
	}
    }

    public double[] getIdealLocations() {
	double[] retval=new double[numPeers];
	for (int i=0; i<numPeers-1; i++) {
	    retval[i]=wrapHigh(list.get(i).location+offset);
	}
	retval[numPeers-1]=wrapHigh(closestPeer.location+offset);
	return retval;
    }

    public List<Double> getUnfilledTargetLocations() {
	List<Double> retval=new ArrayList<Double>();
	for (Slot slot : list) {
	    if (slot.peerImpl==null) {
		retval.add(wrapHigh(slot.location+offset));
	    }
	}
	if (closestPeer.peerImpl==null) {
	    retval.add(wrapHigh(closestPeer.location+offset));
	}
	return retval;
    }

    private static double wrapHigh(double d) {
	if (d>1.0) {
	    return d-1;
	} else {
	    return d;
	}
    }

    public static class Slot {
	//translates to distance-to-next-peer
	double phobia;

	Slot(double phobia) {
	    this.phobia=phobia;
	}
	
	//[0-1] this is the ideal location of the slot *relative* to 0.0
	double location;

	//what peer (if any) is 'occuping' this slot
	Object peerImpl;

	//the location of the peer (as of the last general address update)
	double absolutePeerLocation;
    }

    public static void main(String[] args) {
	int numPeers=Integer.parseInt(args[0]);
	SmallWorldLinkModel m=new SmallWorldLinkModel(numPeers);
	if (args.length > 1) {
	    m.setFreenetLocation(Double.parseDouble(args[1]));
	}
	m.debug();
    }
}
