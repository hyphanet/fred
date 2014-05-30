/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.config;

public interface EnumerableOptionCallback {
    public String[] getPossibleValues();

    /** Return the current value */
    public String get();
}
