package freenet.crypt;
/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/*
 * @deprecated Digests should be able to reinitialize themselves instead
 * Re-precated since I need to create many digests at the same time for the 
 * serial hash.
 **/
public interface DigestFactory {
    
    public Digest getInstance();

}
