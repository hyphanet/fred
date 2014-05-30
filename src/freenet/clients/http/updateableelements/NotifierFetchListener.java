/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http.updateableelements;

//~--- non-JDK imports --------------------------------------------------------

import freenet.clients.http.FProxyFetchListener;

/** This listener notifies the PushDataManager when a download make some progress */
public class NotifierFetchListener implements FProxyFetchListener {
    private PushDataManager pushManager;
    private BaseUpdateableElement element;

    public NotifierFetchListener(PushDataManager pushManager, BaseUpdateableElement element) {
        this.pushManager = pushManager;
        this.element = element;
    }

    @Override
    public void onEvent() {
        pushManager.updateElement(element.getUpdaterId(null));
    }

    @Override
    public String toString() {
        return "NotifierFetchListener[pushManager:" + pushManager + ",element;" + element + "]";
    }
}
