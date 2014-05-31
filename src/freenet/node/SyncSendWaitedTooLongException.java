/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node;

/**
 * This exception is thrown when it we try to do a blocking send of a message, and it takes too long
 * so we timeout. Compare to WaitedTooLongException, which is thrown when we are waiting for clearance
 * from bandwidth limiting and don't get it within a timeout period.
 * @author toad
 */
@SuppressWarnings("serial")
public class SyncSendWaitedTooLongException extends Exception {}
