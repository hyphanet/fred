/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

/*
 * @deprecated Digests should be able to reinitialize themselves instead
 * Re-precated since I need to create many digests at the same time for the 
 * serial hash.
 **/
public interface DigestFactory {
    
    public Digest getInstance();

}
