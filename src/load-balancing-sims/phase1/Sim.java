class Sim
{
	public static void main (String[] args)
	{
		Node n1 = new Node();
		Node n2 = new Node();
		Node n3 = new Node();
		
		// Connect the nodes in a triangle (symmetric link latencies)
		double latency = Math.random() / 10.0;
		n1.connect (n2, latency);
		n2.connect (n1, latency);
		latency = Math.random() / 10.0;
		n1.connect (n3, latency);
		n3.connect (n1, latency);
		latency = Math.random() / 10.0;
		n2.connect (n3, latency);
		n3.connect (n2, latency);
		
		Network.reorder = true;
		
		// Start the nodes pinging
		Event.schedule (n1, Math.random(), Node.GENERATE_PING, null);
		Event.schedule (n2, Math.random(), Node.GENERATE_PING, null);
		Event.schedule (n3, Math.random(), Node.GENERATE_PING, null);
		
		// Run for 10 seconds
		Event.duration = 10.0;
		while (Event.nextEvent()) {}
		
		System.out.println (n1 + " sent " + n1.sent + ", received " + n1.received);
		System.out.println (n2 + " sent " + n2.sent + ", received " + n2.received);
		System.out.println (n3 + " sent " + n3.sent + ", received " + n3.received);
	}
}
