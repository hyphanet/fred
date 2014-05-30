/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

/**
 * @author sdiz
 */
public interface OOMHook {

    /**
     * Handle running low of memory
     *
     * (try to free some cache, save the files, etc).
     */
    void handleLowMemory() throws Exception;

    /**
     * Handle running out of memory
     */
    void handleOutOfMemory() throws Exception;
}
