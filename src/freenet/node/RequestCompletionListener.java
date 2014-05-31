/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

/**
 * Client layer facing callback for a request.
 * Similar to RequestSenderListener, except:
 * 1. We unlock before calling the completion.
 * 2. It converts the completion into a LowLevelPutException if it is a failure.
 * @author toad
 */
public interface RequestCompletionListener {

    /** The request succeeded. The key has been tripped separately. */
    void onSucceeded();

    /** The request failed. */
    void onFailed(LowLevelGetException e);
}
