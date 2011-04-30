/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * Exception thrown when we cannot parse a supplied peers file in
 * SimpleFieldSet format (after it has been turned into a SFS).
 */
public class FSParseException extends Exception {
	private static final long serialVersionUID = -1;
    public FSParseException(Exception e) {
        super(e);
    }
    
    public FSParseException(String msg) {
        super(msg);
    }

    public FSParseException(String msg, NumberFormatException e) {
        super(msg+" : "+e);
        initCause(e);
    }

}
