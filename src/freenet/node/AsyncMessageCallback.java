/*
  AsyncMessageCallback.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.node;

/**
 * Callback interface for async message sending.
 */
public interface AsyncMessageCallback {
    
    /** Called when the packet actually leaves the node.
     * This DOES NOT MEAN that it has been successfully recieved
     * by the partner node (on a lossy transport).
     */
    public void sent();

    /** Called when the packet is actually acknowledged by the
     * other node. This is the end of the transaction. On a
     * non-lossy transport this may be called immediately after
     * sent().
     */
    public void acknowledged();

    /** Called if the node is disconnected while the packet is
     * queued, or after it has been sent. Terminal.
     */
    public void disconnected();
    
    /** Called if the packet is lost due to an internal error. */
    public void fatalError();
}
