/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

public class BulkCallFailureItem {
    public final LowLevelGetException e;
    public final Object token;

    public BulkCallFailureItem(LowLevelGetException e, Object token) {
        this.e = e;
        this.token = token;
    }
}
