package freenet.crypt;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * A token used as an argument to a RandomSource's acceptTimerEntropy.
 * One such token must exist for each timed source.
 **/
public class EntropySource {
    public long lastVal;
    public int lastDelta, lastDelta2;
}
	
