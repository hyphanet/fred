/*
 * fred - MediaType.java - Copyright © 2011 David Roden
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package freenet.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * A media type denotes the content type of a document. A media consists of a
 * top-level type, a subtype, and an optional list of key-value pairs. An
 * example would be “audio/ogg” or “text/html; charset=utf-8.”
 * <p>
 * {@link MediaType}s are immutable. The setter methods (e.g.
 * {@link #setType(String)} and {@link #setSubtype(String)}) return new
 * {@link MediaType} objects with the requested part changed and all other parts
 * copied.
 * <p>
 * Media types are defined in <a href="http://www.ietf.org/rfc/rfc2046.txt">RFC
 * 2046</a>.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
public class MediaType {

	/** The top-level type. */
	private final String type;

	/** The subtype. */
	private final String subtype;

	/** The parameters. */
	private final Map<String, String> parameters = new HashMap<String, String>();

	/**
	 * Creates a new media type by parsing the given string.
	 *
	 * @param mediaType
	 *            The media type to parse
	 * @throws NullPointerException
	 *             if {@code mediaType} is {@code null}
	 * @throws IllegalArgumentException
	 *             if {@code mediaType} is incorrectly formatted, i.e. does not
	 *             contain a slash, or a parameter does not contain an equals
	 *             sign
	 */
	public MediaType(String mediaType) throws NullPointerException, IllegalArgumentException {
		if (mediaType == null) {
			throw new NullPointerException("contentType must not be null");
		}
		int slash = mediaType.indexOf('/');
		if (slash == -1) {
			throw new IllegalArgumentException("mediaType does not contain ‘/’!");
		}
		type = mediaType.substring(0, slash);
		int semicolon = mediaType.indexOf(';');
		if (semicolon == -1) {
			subtype = mediaType.substring(slash + 1);
			return;
		}
		subtype = mediaType.substring(slash + 1, semicolon).trim();
		String[] parameters = mediaType.substring(semicolon + 1).split(";");
		for (String parameter : parameters) {
			int equals = parameter.indexOf('=');
			if (equals == -1) {
				throw new IllegalArgumentException(String.format("Illegal parameter: “%s”", parameter));
			}
			String name = parameter.substring(0, equals).trim().toLowerCase();
			String value = parameter.substring(equals + 1).trim();
			this.parameters.put(name, value);
		}
	}

	/**
	 * Creates a new media type.
	 *
	 * @param type
	 *            The top-level type
	 * @param subtype
	 *            The subtype
	 * @param parameters
	 *            The parameters in key-value pairs, in the order {@code key1},
	 *            {@code value1}, {@code key2}, {@code value2}, …
	 * @throws IllegalArgumentException
	 *             if an invalid number of parameters is given (i.e. the number
	 *             of parameters is odd)
	 */
	public MediaType(String type, String subtype, String... parameters) throws IllegalArgumentException {
		if ((parameters.length & 1) != 0) {
			throw new IllegalArgumentException("Invalid number of parameters given!");
		}
		this.type = type;
		this.subtype = subtype;
		for (int index = 0; index < parameters.length; index += 2) {
			this.parameters.put(parameters[index], parameters[index + 1]);
		}
	}

	/**
	 * Creates a new media type.
	 *
	 * @param type
	 *            The top-level type
	 * @param subtype
	 *            The subtype
	 * @param parameters
	 *            The parameters of the media type
	 */
	public MediaType(String type, String subtype, Map<String, String> parameters) {
		this.type = type;
		this.subtype = subtype;
		this.parameters.putAll(parameters);
	}

	//
	// ACCESSORS
	//

	/**
	 * Returns the top-level type of this media type.
	 *
	 * @return The top-level type
	 */
	public String getType() {
		return type;
	}

	/**
	 * Creates a new media type that has the same subtype and parameters as this
	 * media type and the given type as top-level type.
	 *
	 * @param type
	 *            The top-level type of the new media type
	 * @return The new media type
	 */
	public MediaType setType(String type) {
		return new MediaType(type, subtype, parameters);
	}

	/**
	 * Returns the subtype of this media type.
	 *
	 * @return The subtype
	 */
	public String getSubtype() {
		return subtype;
	}

	/**
	 * Creates a new media type that has the same top-level type and parameters
	 * as this media type and the given subtype as subtype.
	 *
	 * @param subtype
	 *            The subtype of the new media type
	 * @return The new media type
	 */
	public MediaType setSubtype(String subtype) {
		return new MediaType(type, subtype, parameters);
	}

	/**
	 * Returns the value of the parameter with the given name.
	 *
	 * @param name
	 *            The name of the parameter
	 * @return The value of the parameter (or {@code null} if the media type
	 *         does not have a parameter with the given name)
	 */
	public String getParameter(String name) {
		return parameters.get(name.toLowerCase());
	}

	/**
	 * Creates a new media type that has the same top-level type, subtype, and
	 * parameters as this media type but has the parameter with the given name
	 * changed to the given value.
	 *
	 * @param name
	 *            The name of the parameter to change
	 * @param value
	 *            The new value of the parameter
	 * @return The new media type
	 */
	public MediaType setParameter(String name, String value) {
		MediaType newMediaType = new MediaType(type, subtype, parameters);
		newMediaType.parameters.put(name.toLowerCase(), value);
		return newMediaType;
	}

	/**
	 * Creates a new media type that has the same top-level type, subtype, and
	 * parameters as this media type but has the parameter with the given name
	 * removed.
	 *
	 * @param name
	 *            The name of the parameter to remove
	 * @return The new media type
	 */
	public MediaType removeParameter(String name) {
		if (!parameters.containsKey(name.toLowerCase())) {
			return this;
		}
		MediaType newMediaType = new MediaType(type, subtype, parameters);
		newMediaType.parameters.remove(name.toLowerCase());
		return newMediaType;
	}

	//
	// OBJECT METHODS
	//

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		StringBuilder mediaType = new StringBuilder();
		mediaType.append(type).append('/').append(subtype);
		for (Entry<String, String> parameter : parameters.entrySet()) {
			if (parameter.getValue() == null) {
				continue;
			}
			mediaType.append("; ").append(parameter.getKey()).append('=').append(parameter.getValue());
		}
		return mediaType.toString();
	}

}
