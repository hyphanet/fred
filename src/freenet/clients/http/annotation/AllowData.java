/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.annotation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * tag a handleMethodXXX with @AllowData(boolean force)
 * to allow payload on the request<BR>
 * <BR>
 * exception: POST is hard coded with force, RFC says it must have data<BR>
 *            so tagging it does not have effect<BR>
 * <BR>
 * <CODE>@AllowData(true)  // request MUST have data</CODE><BR>
 * <CODE>@AllowData(false)  // request CAN have data</CODE>
 * <BR>
 * @author saces
 *
 */
@Target( METHOD )
@Retention( RUNTIME )
@Documented
public @interface AllowData {
	boolean value() default false;
}
