/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

/**
 * This is a highest level interface for all crypto objects.
 *
 * @author oskar
 */
public interface CryptoElement {

    //public void write(OutputStream o) throws IOException;

    //public String writeAsField();

	public String toLongString();

}
