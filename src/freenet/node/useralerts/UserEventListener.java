/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.useralerts;

/** This interface can be used to register for the alert's changing */
public interface UserEventListener {

    /** Called when alerts changed */
    public void alertsChanged();
}
