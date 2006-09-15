/*
  SerializableToFieldSetBucketUtil.java / Freenet
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

import freenet.crypt.RandomSource;
import freenet.support.SimpleFieldSet;

public class SerializableToFieldSetBucketUtil {

	// FIXME use something other than ResumeException???
	
	public static Bucket create(SimpleFieldSet fs, RandomSource random, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		if(fs == null) return null;
		String type = fs.get("Type");
		if(type == null) {
			throw new CannotCreateFromFieldSetException("No type");
		} else if(type.equals("FileBucket")) {
			return new FileBucket(fs, f);
		} else if(type.equals("PaddedEphemerallyEncryptedBucket")) {
			return new PaddedEphemerallyEncryptedBucket(fs, random, f);
		} else if(type.equals("NullBucket")) {
			return new NullBucket();
		} else if(type.equals("RandomAccessFileBucket")) {
			return new RandomAccessFileBucket(fs, f);
		} else if(type.equals("ReadOnlyFileSliceBucket")) {
			return new ReadOnlyFileSliceBucket(fs);
		} else
			throw new CannotCreateFromFieldSetException("Unrecognized type "+type);
	}

}
