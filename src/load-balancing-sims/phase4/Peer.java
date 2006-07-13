import java.util.LinkedList;
import java.util.Iterator;
import java.util.NoSuchElementException;

class Peer implements EventTarget
{
	public final static double TIMER = 0.5; // Coarse-grained timer, seconds
	public final static double RTO = 4.0; // Retransmission timeout in RTTs
	public final static double FRTO = 1.5; // Fast retx timeout in RTTs
	public final static double RTT_DECAY = 0.9; // Exp moving average
	public final static int MIN_CWIND = 3000; // Minimum congestion window
	public final static int MAX_CWIND = 100000; // Maximum congestion window
	// Note: RWIND must be at least 2 * FRTO * MAX_CWIND
	public final static int RWIND = 400000; // Maximum bytes buffered at rx
	public final static double ALPHA = 0.1615; // AIMD increase parameter
	public final static double BETA = 0.9375; // AIMD decrease parameter
	public final static double GAMMA = 3.0; // Slow start divisor
	
	public int address; // The remote node's address
	private double latency; // Latency of the connection in seconds
	private NetworkInterface net; // The local node's network interface
	
	// Sender
	private double rtt = 3.0; // Estimated round-trip time in seconds
	private double cwind = MIN_CWIND; // Congestion window in bytes
	private boolean slowStart = true; // Are we in the slow start phase?
	
	private double lastTransmission = 0.0; // Clock time
	private double lastCongestionDecrease = 0.0; // Clock time
	private boolean timerRunning = false; // Is the retx timer running?
	
	private int inflight = 0; // Bytes sent but not acked
	private int txSeq = 0; // Sequence number of next outgoing packet
	private LinkedList<Packet> txBuffer; // Retransmission buffer
	private LinkedList<Packet> txQueue; // Packets waiting to be sent
	
	// Receiver
	private int buffered = 0; // Bytes buffered for reassembly
	private int rxSeq = 0; // Sequence number of next incoming packet
	private LinkedList<Packet> rxBuffer; // Reassembly buffer
	private LinkedList<Packet> rxQueue; // Packets waiting to be collected
	
	public Peer (int address, double latency, NetworkInterface net)
	{
		this.address = address;
		this.latency = latency;
		this.net = net;
		txBuffer = new LinkedList<Packet>();
		txQueue = new LinkedList<Packet>();
		rxBuffer = new LinkedList<Packet>();
		rxQueue = new LinkedList<Packet>();
	}
	
	// Queues a packet for transmission
	public void write (Packet p)
	{
		// Length of transmission queue is umlimited - be careful!
		txQueue.add (p);
		if (txQueue.size() == 1) sendData();
	}
	
	// Returns a reassembled packet, or null if there are none waiting
	public Packet read()
	{
		try { return rxQueue.removeFirst(); }
		catch (NoSuchElementException nse) { return null; }
	}
	
	// Send as much data as the receiver window and congestion window allow
	private void sendData()
	{
		Iterator<Packet> i = txQueue.iterator();
		while (i.hasNext()) {
			if (sendData (i.next())) i.remove();
			else break;
		}
	}
	
	// Try to send a packet, return true if it was sent
	private boolean sendData (Packet p)
	{
		// Don't send more bytes than the link can handle
		if (inflight + p.size > cwind) {
			log ("no room in congestion window");
			return false;
		}
		// Return to slow start when the link is idle
		double now = Event.time();
		if (now - lastTransmission > RTO * rtt) {
			log ("returning to slow start");
			cwind = MIN_CWIND;
			slowStart = true;
		}
		lastTransmission = now;
		// Send the packet
		p.seq = txSeq++;
		log ("sending data " + p.seq);
		net.send (p, address, latency);
		// Buffer the packet for retransmission
		p.sent = now;
		inflight += p.size;
		log (inflight + " bytes in flight");
		txBuffer.add (p);
		// Start the coarse-grained retransmission timer if necessary
		if (!timerRunning) {
			log ("starting timer");
			Event.schedule (this, TIMER, CHECK_TIMEOUTS, null);
			timerRunning = true;
		}
		return true;
	}
	
	private void sendAck (int seq)
	{
		Packet p = new Packet (Packet.ACK, 0);
		p.seq = seq; // Explicit ack
		log ("sending ack " + seq);
		net.send (p, address, latency);
	}
	
	// Called by Node when a packet arrives
	public void handlePacket (Packet p)
	{
		switch (p.type) {
			case Packet.DATA:
			handleData (p);
			break;
			
			case Packet.ACK:
			handleAck (p);
			break;
		}
	}
	
	private void handleData (Packet p)
	{
		log ("received data " + p.seq);
		// Is this the packet we've been waiting for?
		if (p.seq == rxSeq) {
			log ("data in order");
			rxSeq++;
			rxQueue.add (p);
			// Reassemble contiguous packets
			Iterator<Packet> i = rxBuffer.iterator();
			while (i.hasNext()) {
				Packet q = i.next();
				if (q.seq == rxSeq) {
					log ("adding contiguous data " + q.seq);
					i.remove();
					buffered -= q.size;
					rxQueue.add (q);
					rxSeq++;
				}
				else break;
			}
			log (buffered + " bytes buffered");
			log ("expecting data " + rxSeq);
			// FIXME: notify the node that there are packets waiting
		}
		else if (p.seq > rxSeq) {
			log ("data out of order, expected " + rxSeq);
			// Buffer the packet until all previous packets arrive
			int index;
			Iterator<Packet> i = rxBuffer.iterator();
			for (index = 0; i.hasNext(); index++) {
				Packet q = i.next();
				if (q.seq == p.seq) {
					log ("duplicate data " + p.seq);
					sendAck (p.seq);
					return;
				}
				if (q.seq > p.seq) break;
			}
			if (buffered + p.size > RWIND) {
				// This shouldn't happen under normal conditions
				log ("no space in buffer - packet dropped");
				return;
			}
			buffered += p.size;
			log (buffered + " bytes buffered");
			rxBuffer.add (index, p);
			// DEBUG
			if (!rxBuffer.isEmpty()) {
				for (Packet z : rxBuffer)
					System.out.print (z.seq + " ");
				System.out.println();
			}
		}
		else log ("duplicate data " + p.seq); // Ack may have been lost
		sendAck (p.seq);
	}
	
	private void handleAck (Packet p)
	{
		log ("received ack " + p.seq);
		double now = Event.time();
		boolean windowIncreased = false;
		Iterator<Packet> i = txBuffer.iterator();
		while (i.hasNext()) {
			Packet q = i.next();
			double age = now - q.sent;
			// Explicit ack
			if (q.seq == p.seq) {
				log ("data " + q.seq + " acknowledged");
				i.remove();
				inflight -= q.size;
				log (inflight + " bytes in flight");
				// Increase the congestion window
				if (slowStart) cwind += q.size / GAMMA;
				else cwind += q.size * q.size * ALPHA / cwind;
				if (cwind > MAX_CWIND) cwind = MAX_CWIND;
				log ("congestion window increased to " + cwind);
				// Update the average round-trip time
				rtt = rtt * RTT_DECAY + age * (1.0 - RTT_DECAY);
				log ("round-trip time " + age);
				log ("average round-trip time " + rtt);
				windowIncreased = true;
				break;
			}
			// Fast retransmission
			if (q.seq < p.seq && age > FRTO * rtt) {
				q.sent = now;
				log ("fast retransmitting data " + q.seq);
				log (inflight + " bytes in flight");
				net.send (q, address, latency);
				decreaseCongestionWindow (now);
			}
		}
		if (windowIncreased) sendData();
	}
	
	private void decreaseCongestionWindow (double now)
	{
		// The congestion window should only be decreased once per RTT
		if (now - lastCongestionDecrease < rtt) return;
		lastCongestionDecrease = now;
		cwind *= BETA;
		if (cwind < MIN_CWIND) cwind = MIN_CWIND;
		log ("congestion window decreased to " + cwind);
		// The slow start phase ends when the first packet is lost
		if (slowStart) {
			log ("leaving slow start");
			slowStart = false;
		}
	}
	
	private void log (String message)
	{
		Event.log (net.address + ":" + address + " " + message);
	}
	
	// Event callback
	private void checkTimeouts()
	{
		log ("checking timeouts");
		// If there are no packets in flight, stop the timer
		if (txBuffer.isEmpty()) {
			log ("stopping timer");
			timerRunning = false;
			return;
		}
		double now = Event.time();
		for (Packet p : txBuffer) {
			// Slow retransmission
			if (now - p.sent > RTO * rtt) {
				p.sent = now;
				log ("retransmitting data " + p.seq);
				log (inflight + " bytes in flight");
				net.send (p, address, latency);
				decreaseCongestionWindow (now);
			}
		}
		// Reset the timer
		Event.schedule (this, TIMER, CHECK_TIMEOUTS, null);
	}
	
	// EventTarget interface
	public void handleEvent (int type, Object data)
	{
		if (type == CHECK_TIMEOUTS) checkTimeouts();
	}
	
	// Each EventTarget class has its own event codes
	private final static int CHECK_TIMEOUTS = 1;
}
