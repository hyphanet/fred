/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

/**
 * Something that wants to listen for nodeToNodeMessage's.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public interface NodeToNodeMessageListener {
    public void handleMessage(byte[] data, boolean fromDarknet, PeerNode source, int type);
}
