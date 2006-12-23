/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.config;

import freenet.config.Config;
import freenet.config.SubConfig;
import junit.framework.TestCase;

/**
 * Test case for the {@link freenet.config.Config} class.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class ConfigTest extends TestCase {
	Config conf;
	SubConfig sc;
	
	public ConfigTest(String name) {
		super(name);
	}
	
	protected void setUp() throws Exception {
		super.setUp();
		conf = new Config();
		sc = new SubConfig("testing", conf);
	}
	
	public void testConfig() {
		assertNotNull(new Config());
	}

	public void testRegister() {
		/* test if we can register */
		assertNotNull(new SubConfig("testing1%Ή~#{[|`^@ιθ&η", conf));
		
		/* test if it prevents multiple registrations */
		try{
			conf.register(sc);
		}catch (IllegalArgumentException ie){
			return;
		}
		fail();
	}

	public void testGetConfigs() {
		assertNotNull(conf.getConfigs());
		assertFalse(new Config().getConfigs().equals(conf));
		assertEquals(1 , conf.getConfigs().length);
		assertSame(sc, conf.getConfigs()[0]);
	}

	public void testGet() {
		assertSame(sc, conf.get("testing"));
	}

}
