/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.keys;

//~--- non-JDK imports --------------------------------------------------------

import freenet.crypt.CryptFormatException;

public class PubkeyVerifyException extends KeyVerifyException {
    private static final long serialVersionUID = 1L;

    public PubkeyVerifyException(CryptFormatException e) {
        super(e);
    }

    public PubkeyVerifyException(String msg) {
        super(msg);
    }
}
