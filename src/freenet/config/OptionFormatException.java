/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.config;

/**
 * Thrown when a format error occurs, and we cannot parse the string set into the appropriate
 * type.
 */
public class OptionFormatException extends InvalidConfigValueException {
    private static final long serialVersionUID = -1;

    public OptionFormatException(String msg) {
        super(msg);
    }
}
