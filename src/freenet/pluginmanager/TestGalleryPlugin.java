package freenet.pluginmanager;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.Random;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;

public class TestGalleryPlugin implements FredPlugin {
	boolean goon = true;
	Random rnd = new Random();
	PluginRespirator pr;
	public void terminate() {
		goon = false;
	}
	
	private String getArrayElement(String[] array, int element) {
		try {
			return array[element];
		} catch (Exception e) {
			//e.printStackTrace();
			return "";
		}
	}
	
	public String handleHTTPGet(String path) {
		StringBuffer out = new StringBuffer();
		String[] pathelements = path.split("\\?");
		String uri = pathelements[0];
		String[] getelements = getArrayElement(pathelements, 1).split("\\?");
		int page = 1;
		for (int i = 0; i < getelements.length ; i++) {
			if (getelements[i].startsWith("page="))
				page = Integer.parseInt(getelements[i].substring("page=".length()));
		}
			
		
		try {
			/* Cache later! */
			HighLevelSimpleClient hlsc = pr.getHLSimpleClient();
			String imglist = new String(hlsc.fetch(new FreenetURI(uri)).asByteArray()).trim();
			imglist = imglist.replaceAll("\r","\n");
			imglist = imglist.replaceAll("\n\n", "\n");
			imglist = imglist.replaceAll("\n\n", "\n");
			imglist = imglist.replaceAll("\n\n", "\n");
			imglist = imglist.replaceAll("\n\n", "\n");
			imglist = imglist.replaceAll("\n\n", "\n");
			/* /Cache! */
			
			String[] imgarr = imglist.split("\n");
			//imgarr[0] == title;
			out.append("<HTML><HEAD><TITLE>" + imgarr[0] + "</TITLE></HEAD><BODY>\n");
			out.append("<CENTER><H1>" + imgarr[0] + "</H1><BR/>Page " + page + "<BR/><BR/>\n");
			out.append("<table><tr>\n");
			int images = 0;
			int flush = (page - 1)*6*4;
			for(int i = 1 ; (i < imgarr.length && images < 6*4); i++) {
				// url | name | size
				if (imgarr[i].trim().length() < 5)
					continue;
				if (flush > 0) {
					flush--;
					continue;
				}
				images++;
				
				String imginfo[] = imgarr[i].split("\\|");
				String iname = getArrayElement(imginfo, 1).trim();
				String isname = iname;
				if (iname.length() > 15)
					isname = iname.substring(0,11) + "..." + iname.substring(iname.lastIndexOf("."));
				
				// f2="`echo "$f" | rev | cut -d . -f2- | rev | cut -c-13`...`echo "$f" | rev | cut -d . -f1 | rev`"
				//String isize = getArrayElement(imginfo, 2).trim();
				String iurl = getArrayElement(imginfo, 0).trim();
				iurl = iurl.replaceAll("^URI: ", "");
				iurl = iurl.replaceAll("^freenet:", "");
				iurl = "/" + iurl;
				
				
				
				out.append("<td align=\"center\" valign=\"top\" width=\"102px\">\n");
				out.append("  <a title=\""+iname+"\" href=\"" + iurl + "\"><img src=\"" + iurl + "\" border=\"0\" width=\"100\"><br/>\n");
				out.append("  <font size=\"-2\">\"" + isname + "\"</font>\n");
				out.append("  </a>\n");
				for (int j = 2 ; j < imginfo.length ; j++)
					out.append("  <br><font size=\"-2\">" + imginfo[j].trim() + "</font>\n");
				out.append("</td>\n");
				
				// new row?
				if (i%6 == 0) {
					out.append("</tr><tr>\n");
				}
			}
			out.append("</tr><table>\n");
			for (int pg = 1 ; pg <= (int)Math.ceil((imgarr.length-1)/(6*4)) ; pg++) {
				out.append("&nbsp;");
				if (pg != page)
					out.append("<a href=\""+uri+"?page="+pg+"\">["+pg+"]</a>");
				else
					out.append("["+pg+"]");
				out.append("&nbsp;\n");
			}
			
			
			out.append("</CENTER></BODY></HTML>");
			return out.toString();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			return e.toString();// e.printStackTrace();
		}
	}

	public void runPlugin(PluginRespirator pr) {
		this.pr = pr;
		
		//int i = (int)System.currentTimeMillis()%1000;
		while(goon) {
			System.err.println("Heartbeat from gallery-plugin: " + (new Date()));
			/*
			FetchResult fr;
			try {
				fr = pr.getHLSimpleClient().fetch(new FreenetURI("freenet:CHK@j-v1zc0cuN3wlaCpxlKd6vT6c1jAnT9KiscVjfzLu54,q9FIlJSh8M1I1ymRBz~A0fsIcGkvUYZahZb5j7uepLA,AAEA--8"));
				System.err.println("  Got data from key, length = " + fr.size() + " Message: "
						+ new String(fr.asByteArray()).trim());
			} catch (Exception e) {
			}
			*/
			try {
				Thread.sleep(300000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			}
		}
	}
	
	public boolean handles(int thing) {
		return ((thing == FredPlugin.handleFproxy) ||
				(thing == FredPlugin.handleFproxy) ||
				(thing == FredPlugin.handleFproxy));
	}

}
