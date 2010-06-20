/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.transport.ip;

import freenet.io.AddressIdentifier;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class HostnameUtil {
        private static volatile boolean logDEBUG;

        static {
                Logger.registerLogThresholdCallback(new LogThresholdCallback(){
                        @Override
                        public void shouldUpdate(){
                                logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
                        }
                });
        }

        /**
         *
         * @param hn
         * @param allowIPAddress
         * @return
         */
        public static boolean isValidHostname(String hn, boolean allowIPAddress) {
		if(allowIPAddress) {	
			// debugging log messages because AddressIdentifier doesn't appear to handle all IPv6 literals correctly, such as "fe80::204:1234:dead:beef"
			AddressIdentifier.AddressType addressType = AddressIdentifier.getAddressType(hn, true);
			if(logDEBUG)Logger.debug(null, "Address type of '"+hn+"' appears to be '"+addressType+ '\'');
			if(!addressType.toString().equals("Other")) {
				// the address typer thinks it's either an IPv4 or IPv6 IP address
				return true;
			}
		}
		// NOTE: It is believed that this code supports PUNYCODE based
		//       ASCII Compatible Encoding (ACE) IDNA labels as
		//       described in RFC3490.  Such an assertion has not be
		//       thoroughly tested.
		if(!hn.matches("(?:[-!#\\$%&'\\*+\\\\/0-9=?A-Z^_`a-z{|}]+\\.)+[a-zA-Z]{2,6}")) {
			System.err.println("Failed to match "+hn+" as a hostname or IPv4/IPv6 IP address");
			return false;
		}
		return true;
	}

}
