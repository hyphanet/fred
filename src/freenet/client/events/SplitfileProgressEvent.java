/*
  SplitfileProgressEvent.java / Freenet
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

package freenet.client.events;

import freenet.support.Logger;

public class SplitfileProgressEvent implements ClientEvent {

	public static int code = 0x07;
	
	public final int totalBlocks;
	public final int fetchedBlocks;
	public final int failedBlocks;
	public final int fatallyFailedBlocks;
	public int minSuccessfulBlocks;
	public final boolean finalizedTotal;
	
	public SplitfileProgressEvent(int totalBlocks, int fetchedBlocks, int failedBlocks, 
			int fatallyFailedBlocks, int minSuccessfulBlocks, boolean finalizedTotal) {
		this.totalBlocks = totalBlocks;
		this.fetchedBlocks = fetchedBlocks;
		this.failedBlocks = failedBlocks;
		this.fatallyFailedBlocks = fatallyFailedBlocks;
		this.minSuccessfulBlocks = minSuccessfulBlocks;
		this.finalizedTotal = finalizedTotal;
	}

	public String getDescription() {
		StringBuffer sb = new StringBuffer();
		sb.append("Completed ");
		if((minSuccessfulBlocks == 0) && (fetchedBlocks == 0))
			minSuccessfulBlocks = 1;
		if(minSuccessfulBlocks == 0) {
			if(Logger.globalGetThreshold() > Logger.MINOR)
				Logger.error(this, "minSuccessfulBlocks=0, fetchedBlocks="+fetchedBlocks+", totalBlocks="+totalBlocks+
						", failedBlocks="+failedBlocks+", fatallyFailedBlocks="+fatallyFailedBlocks+", finalizedTotal="+finalizedTotal);
			else
				Logger.error(this, "minSuccessfulBlocks=0, fetchedBlocks="+fetchedBlocks+", totalBlocks="+totalBlocks+
						", failedBlocks="+failedBlocks+", fatallyFailedBlocks="+fatallyFailedBlocks+", finalizedTotal="+finalizedTotal, new Exception("debug"));
		} else {
			sb.append((100*(fetchedBlocks)/minSuccessfulBlocks));
			sb.append('%');
		}
		sb.append(' ');
		sb.append(fetchedBlocks);
		sb.append('/');
		sb.append(minSuccessfulBlocks);
		sb.append(" (failed ");
		sb.append(failedBlocks);
		sb.append(", fatally ");
		sb.append(fatallyFailedBlocks);
		sb.append(", total ");
		sb.append(totalBlocks);
		sb.append(") ");
		sb.append(finalizedTotal ? " (finalized total)" : "");
		return sb.toString();
	}

	public int getCode() {
		return code;
	}

}
