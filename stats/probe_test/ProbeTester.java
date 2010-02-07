import java.util.Random;

class ProbeTester {
	public static void main(String[] args) {
		int seed = 0;
		int nProbes = 120;
		if (args.length > 0) {
			//random seed
			try {
				seed = Integer.parseInt(args[0]);
				System.err.println("Using random seed " + seed);
			} catch (NumberFormatException e) {
				System.err.println("Error parsing seed.");
			}
		}
		if (args.length > 1) {
			//nProbes
			try {
				nProbes = Integer.parseInt(args[1]);
				System.err.println("Performing " + nProbes + " probes.");
			} catch (NumberFormatException e) {
				System.err.println("Error parsing probe count.");
			}
		}


		Random rand = new MersenneTwister(seed);
		int sleepInterval = 30000;	//msecs
		double[] probeLocs = new double[nProbes];

		for (int i = 0; i < nProbes; i++) {
			probeLocs[i] = rand.nextDouble();
		}

		//send probes
		for (int i = 0; i < nProbes; i++) {
			System.out.println("PROBE:" + probeLocs[i]);
			try {
				Thread.sleep(sleepInterval + rand.nextInt(sleepInterval / 10));
			} catch (InterruptedException e) {
				//do nothing
			}
		}

		try {
			Thread.sleep(4*sleepInterval);
		} catch (InterruptedException e) {
			//do nothing
		}

		System.out.println("quit");
		try {
			Thread.sleep(10);
		} catch (InterruptedException e) {
		}
	}
}
