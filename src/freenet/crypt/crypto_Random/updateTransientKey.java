import java.util.*;
import java.lang.*;
package freenet.crypt;
public class updateTransientKey extends Thread{
	Responder rs;
	public int hkrPeriod;
	public updateTransientKey(Responder rs)
	{
		this.rs=rs;
	}
	public void run()
	{
		while(true)
		{
			try
			{
				sleep(hkrPeriod);
				rs.getHkr();
			}catch(Exception e){
				e.printStackTrace();
			}
		}
	}
}
			

