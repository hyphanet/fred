/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

/**
 * A token used as an argument to a RandomSource's acceptTimerEntropy.
 * One such token must exist for each timed source.
 **/
public class EntropySource {
    public long lastVal;
    public int lastDelta, lastDelta2;
}
	
