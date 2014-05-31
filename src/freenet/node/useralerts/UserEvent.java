/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.useralerts;

public interface UserEvent extends UserAlert {
    public enum Type {
        Announcer(true), GetCompleted, PutCompleted, PutDirCompleted;

        private boolean unregisterIndefinitely;

        private Type() {
            unregisterIndefinitely = false;
        }

        private Type(boolean unregisterIndefinetely) {
            this.unregisterIndefinitely = unregisterIndefinetely;
        }

        /**
         *
         * @return true if the unregistration of one event of this type
         *         should prevent future events of the same type from being displayed
         */
        public boolean unregisterIndefinitely() {
            return unregisterIndefinitely;
        }
    }

    ;

    /**
     *
     * @return The type of the event
     */
    public Type getEventType();
}
