/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

/**
 * Indicates an attempt to link two DoublyLinkedList.Item's,
 * neither of which are a member of a DoublyLinkedList.
 * @author tavin
 */
public class VirginItemException extends RuntimeException {
    private static final long serialVersionUID = -1;

    VirginItemException(DoublyLinkedList.Item<?> item) {
        super(item.toString());
    }
}
