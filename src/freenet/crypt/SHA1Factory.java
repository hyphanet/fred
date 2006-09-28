/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

public class SHA1Factory implements DigestFactory {

    public Digest getInstance() {
	return SHA1.getInstance();
    }
    
}
