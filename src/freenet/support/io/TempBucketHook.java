/*
  TempBucketHook.java / Freenet
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

package freenet.support.io;

import java.io.IOException;

public interface TempBucketHook {
    
    /** Allocate space for a write making the file larger
     * Call this before writing a file, make sure you call shrinkFile if the write fails
     * @param curLength the length of the file before the write
     * @param finalLength the length of the file after the write
     * @throws IOException if insufficient space
     */
    void enlargeFile(long curLength, long finalLength) throws IOException;
    
    /** Deallocate space after a write enlarging the file fails
     * Call this if enlargeFile was called but the write failed.
     * Also call it if you want to truncate a file
     * @param curLength original length before write
     * @param finalLength length the file would have been if the write had succeeded
     */
    void shrinkFile(long curLength, long finalLength);
    
    /** Deallocate space for a temp file, AFTER successful delete completed
     */
    void deleteFile(long curLength);
    
    /** Allocate space for a temp file, before actually creating it
     */
    void createFile(long curLength) throws IOException;

}
