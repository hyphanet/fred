/*
  StringArrOption.java / Freenet
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

package freenet.config;

import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;

public class StringArrOption extends Option {

    private final String defaultValue;
    private final StringArrCallback cb;
	private String currentValue;
	
    public static final String delimiter = ";";
	
	public StringArrOption(SubConfig conf, String optionName, String defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringArrCallback cb) {
		super(conf, optionName, sortOrder, expert, forceWrite, shortDesc, longDesc);
		this.defaultValue = (defaultValue==null)?"":defaultValue;
		this.cb = cb;
		this.currentValue = (defaultValue==null)?"":defaultValue;
	}
	
	public StringArrOption(SubConfig conf, String optionName, String defaultValue[], int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringArrCallback cb) {
		this(conf, optionName, arrayToString(defaultValue), sortOrder, expert, forceWrite, shortDesc, longDesc, cb);
	}
	
	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public String[] getValue() {
		return getValueString().split(delimiter);
	}

	public void setValue(String val) throws InvalidConfigValueException {
		setInitialValue(val);
		cb.set(this.currentValue);
	}
	
	public String getValueString() {
		if(config.hasFinishedInitialization())
			currentValue = cb.get();
		return currentValue;
	}
	
	public void setInitialValue(String val) throws InvalidConfigValueException {
		this.currentValue = val;
	}
	
	
	public static String arrayToString(String[] arr) {
		if (arr == null)
			return null;
		StringBuffer sb = new StringBuffer();
		for (int i = 0 ; i < arr.length ; i++)
			sb.append(arr[i] + delimiter);
		return sb.toString();
	}
	
	public static String encode(String s) {
		return URLEncoder.encode(s);
	}
	
	public static String decode(String s) {
		try {
			return URLDecoder.decode(s);
		} catch (URLEncodedFormatException e) {
			return null;
		}
	}

	public String getDefaultValue() {
		return defaultValue;
	}

	public boolean isDefault() {
		return currentValue == null ? false : currentValue.equals(defaultValue);
	}
	
	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
