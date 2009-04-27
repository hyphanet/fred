/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

// for backward compatibility
// should use ClientPutCallback / ClientGetCallback in most case
public interface ClientCallback extends ClientGetCallback, ClientPutCallback {

}
