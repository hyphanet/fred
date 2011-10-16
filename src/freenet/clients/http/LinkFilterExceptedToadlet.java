/*
 * fred - LinkFilteExceptedToadlet.java - Copyright © 2011 David Roden
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

package freenet.clients.http;

import java.net.URI;

/**
 * Interface for {@link Toadlet}s that want to asked when a link to it is being
 * filtered.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
public interface LinkFilterExceptedToadlet {

	/**
	 * Returns whether the given should be excepted from being filtered.
	 *
	 * @param link
	 *            The link to check
	 * @return {@code true} if the link should not be filtered, {@code false} if
	 *         it should be filtered
	 */
	public boolean isLinkExcepted(URI link);

}
