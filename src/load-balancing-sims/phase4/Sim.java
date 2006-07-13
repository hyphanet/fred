// Interesting parameters to play with: txSpeed and rxSpeed, retransmission
// timeout, window size, AIMD increase and decrease (Peer.java), queue size
// (NetworkInterface.java), packet size (Node.java).

class Sim
{
	public static void main (String[] args)
	{		
		double txSpeed = 15000, rxSpeed = 15000; // Bytes per second
		// rxSpeed = Math.exp (rand.nextGaussian() + 11.74);
		// txSpeed = rxSpeed / 5.0;
		
		Network.reorder = true;
		Network.lossRate = 0.001;
		
		Node n0 = new Node (txSpeed, rxSpeed);
		Node n1 = new Node (txSpeed, rxSpeed);
		Node n2 = new Node (txSpeed, rxSpeed);
		
		n0.connect (n1, 0.1);
		n0.connect (n2, 0.1);
		n1.connect (n0, 0.1);
		n1.connect (n2, 0.1);
		n2.connect (n0, 0.1);
		n2.connect (n1, 0.1);
		
		Event.schedule (n0, Math.random(), Node.START, null);
		Event.schedule (n1, Math.random(), Node.START, null);
		Event.schedule (n2, Math.random(), Node.START, null);
		
		// Run the simulation
		Event.run();
	}
}
