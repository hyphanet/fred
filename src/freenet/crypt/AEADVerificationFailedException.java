/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.crypt;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

/** Thrown when the final MAC fails on an AEADInputStream. */
public class AEADVerificationFailedException extends IOException {}
