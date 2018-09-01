/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;


/**
 * A {@link RandomSource} which supports writing a seed to disk, and bootstrapping future instances
 * of it by reading the seed file from disk.<br>
 * If the random number generator used by the node implements this interface, the node is obliged
 * to call {@link #write_seed(boolean)} during shutdown to store the seed to disk.<br><br>
 * 
 * This is a benefit in security as entropy can be preserved across restarts of Freenet, to cope
 * with systems which offer low entropy on the system random number generator such as /dev/random.
 * <br><br>
 * 
 * TODO: Code quality: Please move read_seed() from {@link Yarrow} to this as well.
 */
public interface PersistentRandomSource {

    /**
     * Explanation of the purpose of this mechanism is at its interface
     * {@link PersistentRandomSource}.
     * 
     * @param force
     *     If false, the implementation might decide to ignore this function call if the seed file
     *     was written to disk a short time ago already.
     */
    void write_seed(boolean force);

}