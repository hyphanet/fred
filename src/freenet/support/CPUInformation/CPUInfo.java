/*
  CPUInfo.java / Freenet
  Created on Jul 16, 2004
  Copyright (C) 2004 Iakin 
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.support.CPUInformation;

/**
 * @author Iakin
 * An interface for classes that provide lowlevel information about CPU's
 *
 * free (adj.): unencumbered; not under the control of others
 * Written by Iakin in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */

public interface CPUInfo
{
	/**
	 * @return A string indicating the vendor of the CPU.
	 */
	public String getVendor();
	/**
	 * @return A string detailing what type of CPU that is present in the machine. I.e. 'Pentium IV' etc.
	 * @throws UnknownCPUException If for any reson the retrieval of the requested information
	 * failed. The message encapsulated in the execption indicates the 
	 * cause of the failure.
	 */
	public String getCPUModelString() throws UnknownCPUException;
	
	/**
	 * @return true iff the CPU support the MMX instruction set.
	 */
	public boolean hasMMX();
	/**
	 * @return true iff the CPU support the SSE instruction set.
	 */
	public boolean hasSSE();
	/**
	 * @return true iff the CPU support the SSE2 instruction set.
	 */
	public boolean hasSSE2();
	
}