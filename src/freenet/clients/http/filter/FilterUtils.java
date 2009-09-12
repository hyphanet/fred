package freenet.clients.http.filter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import freenet.support.HTMLNode;

public class FilterUtils {

	//Basic Data types
	public static boolean isInteger(String strValue)
	{
		try
		{
			int intValue=Integer.parseInt(strValue);
			if(intValue>-2147483648 && intValue<2147483647)
				return true;
			else
				return false;
		}
		catch(Exception e)
		{
			return false;
		}
	}
	public static boolean isNumber(String strNumber)
	{
		try
		{
			boolean containsE=false;
			String strDecimal,strInteger=null;
			if(strNumber.indexOf('e')>0)
			{
				containsE=true;
				strDecimal=strNumber.substring(0,strNumber.indexOf('e'));
				strInteger=strNumber.substring(strDecimal.indexOf('e')+1,strNumber.length());
			}
			else if(strNumber.indexOf('E')>0)
			{
				containsE=true;
				strDecimal=strNumber.substring(0,strNumber.indexOf('E'));
				strInteger=strNumber.substring(strDecimal.indexOf('E')+1,strNumber.length());
			}
			else
				strDecimal=strNumber;
			Double.parseDouble(strDecimal);
			if(containsE)
				return isInteger(strInteger);
			else
				return true;
			}
		catch(Exception e)
		{
			return false;
		}
	}
	private final static HashSet<String> allowedUnits=new HashSet<String>();
	static
	{
		allowedUnits.add("em");
		allowedUnits.add("ex");
		allowedUnits.add("px");
		allowedUnits.add("in");
		allowedUnits.add("cm");
		allowedUnits.add("mm");
		allowedUnits.add("pt");
		allowedUnits.add("pc");
	}
	public static boolean isPercentage(String value)
	{
		System.out.println("isPercentage is called with:"+value);
		if (value.length()>2 && value.charAt(value.length()-1)=='%') //Valid percentage X%
		{
			try
			{
				int intvalue=Integer.parseInt(value.substring(0,value.length()-1));
				System.out.println("Trying to parse"+value.substring(0,value.length()-1));
				if(intvalue>0)
					return true;
			}
			catch(Exception e) { }
		}
		return false;
	}

	public static boolean isLength(String value,boolean isSVG) //SVG lengths allow % values
	{
		String lengthValue=null;
		value=value.trim();
		if(isSVG)
		{
		if(value.charAt(value.length()-1)=='%')
			lengthValue=value.substring(0,value.length()-2);
		}
		if(lengthValue==null && value.length()>2) //Valid unit Vxx where xx is unit or V
		{
			String unit=value.substring(value.length()-2, value.length());
			if(allowedUnits.contains(unit))
				lengthValue=value.substring(0,value.length()-2);
			else
				lengthValue=value.substring(0,value.length());
		}
		else
			lengthValue=value.substring(0,value.length());
		System.out.println("Last character: "+value.charAt(value.length()-1));
		System.out.println("lengthValue: "+lengthValue);
		try
		{
			Integer.parseInt(lengthValue);
			return true;
		}
		catch(Exception e){ }
		try
		{
			double dval=Double.parseDouble(lengthValue);
			if(!(Double.isInfinite(dval) || Double.isNaN(dval)))
				return true;
		}
		catch(Exception e){ }
		return false;
	}
	public static boolean isAngle(String value)
	{
		boolean isValid=true;
		int index=-1;
		if(value.indexOf("deg")>-1)
		{
			index=value.indexOf("deg");
			String secondpart=value.substring(index,value.length()).trim();
			if(!("deg".equals(secondpart)))
				isValid=false;
		}
		else if(value.indexOf("grad")>-1)
		{
			index=value.indexOf("grad");
			String secondpart=value.substring(index,value.length()).trim();

			if(!("grad".equals(secondpart)))
				isValid=false;
		}
		else if(value.indexOf("rad")>-1)
		{
			index=value.indexOf("rad");
			String secondpart=value.substring(index,value.length()).trim();

			if(!("rad".equals(secondpart)))
				isValid=false;
		}
		if(index!=-1 && isValid)
		{
			String firstPart=value.substring(0,index);
			try
			{
				Float.parseFloat(firstPart);
				return true;
			}
			catch(Exception e)
			{
			}
		}
		return false;
	}
	private final static HashSet<String> SVGcolorKeywords=new HashSet<String>();
	static
	{
		SVGcolorKeywords.add("aliceblue");
		SVGcolorKeywords.add("antiquewhite");
		SVGcolorKeywords.add("aqua");
		SVGcolorKeywords.add("aquamarine");
		SVGcolorKeywords.add("azure");
		SVGcolorKeywords.add("beige");
		SVGcolorKeywords.add("bisque");
		SVGcolorKeywords.add("black");
		SVGcolorKeywords.add("blanchedalmond");
		SVGcolorKeywords.add("blue");
		SVGcolorKeywords.add("blueviolet");
		SVGcolorKeywords.add("brown");
		SVGcolorKeywords.add("burlywood");
		SVGcolorKeywords.add("cadetblue");
		SVGcolorKeywords.add("chartreuse");
		SVGcolorKeywords.add("chocolate");
		SVGcolorKeywords.add("coral");
		SVGcolorKeywords.add("cornflowerblue");
		SVGcolorKeywords.add("cornsilk");
		SVGcolorKeywords.add("crimson");
		SVGcolorKeywords.add("cyan");
		SVGcolorKeywords.add("darkblue");
		SVGcolorKeywords.add("darkcyan");
		SVGcolorKeywords.add("darkgoldenrod");
		SVGcolorKeywords.add("darkgray");
		SVGcolorKeywords.add("darkgreen");
		SVGcolorKeywords.add("darkgrey");
		SVGcolorKeywords.add("darkkhaki");
		SVGcolorKeywords.add("darkmagenta");
		SVGcolorKeywords.add("darkolivegreen");
		SVGcolorKeywords.add("darkorange");
		SVGcolorKeywords.add("darkorchid");
		SVGcolorKeywords.add("darkred");
		SVGcolorKeywords.add("darksalmon");
		SVGcolorKeywords.add("darkseagreen");
		SVGcolorKeywords.add("darkslateblue");
		SVGcolorKeywords.add("darkslategray");
		SVGcolorKeywords.add("darkslategrey");
		SVGcolorKeywords.add("darkturquoise");
		SVGcolorKeywords.add("darkviolet");
		SVGcolorKeywords.add("deeppink");
		SVGcolorKeywords.add("deepskyblue");
		SVGcolorKeywords.add("dimgray");
		SVGcolorKeywords.add("dimgrey");
		SVGcolorKeywords.add("dodgerblue");
		SVGcolorKeywords.add("firebrick");
		SVGcolorKeywords.add("floralwhite");
		SVGcolorKeywords.add("forestgreen");
		SVGcolorKeywords.add("fuchsia");
		SVGcolorKeywords.add("gainsboro");
		SVGcolorKeywords.add("ghostwhite");
		SVGcolorKeywords.add("gold");
		SVGcolorKeywords.add("goldenrod");
		SVGcolorKeywords.add("gray");
		SVGcolorKeywords.add("grey");
		SVGcolorKeywords.add("green");
		SVGcolorKeywords.add("greenyellow");
		SVGcolorKeywords.add("honeydew");
		SVGcolorKeywords.add("hotpink");
		SVGcolorKeywords.add("indianred");
		SVGcolorKeywords.add("indigo");
		SVGcolorKeywords.add("ivory");
		SVGcolorKeywords.add("khaki");
		SVGcolorKeywords.add("lavender");
		SVGcolorKeywords.add("lavenderblush");
		SVGcolorKeywords.add("lawngreen");
		SVGcolorKeywords.add("lemonchiffon");
		SVGcolorKeywords.add("lightblue");
		SVGcolorKeywords.add("lightcoral");
		SVGcolorKeywords.add("lightcyan");
		SVGcolorKeywords.add("lightgoldenrodyellow");
		SVGcolorKeywords.add("lightgray");
		SVGcolorKeywords.add("lightgreen");
		SVGcolorKeywords.add("lightgrey");
		SVGcolorKeywords.add("lightpink");
		SVGcolorKeywords.add("lightsalmon");
		SVGcolorKeywords.add("lightseagreen");
		SVGcolorKeywords.add("lightskyblue");
		SVGcolorKeywords.add("lightslategray");
		SVGcolorKeywords.add("lightslategrey");
		SVGcolorKeywords.add("lightsteelblue");
		SVGcolorKeywords.add("lightyellow");
		SVGcolorKeywords.add("lime");
		SVGcolorKeywords.add("limegreen");
		SVGcolorKeywords.add("linen");
		SVGcolorKeywords.add("magenta");
		SVGcolorKeywords.add("maroon");
		SVGcolorKeywords.add("mediumaquamarine");
		SVGcolorKeywords.add("mediumblue");
		SVGcolorKeywords.add("mediumorchid");
		SVGcolorKeywords.add("thistle");
		SVGcolorKeywords.add("tomato");
		SVGcolorKeywords.add("turquoise");
		SVGcolorKeywords.add("violet");
		SVGcolorKeywords.add("wheat");
		SVGcolorKeywords.add("white");
		SVGcolorKeywords.add("whitesmoke");
		SVGcolorKeywords.add("yellow");
		SVGcolorKeywords.add("yellowgreen");
	}
	private final static HashSet<String> CSScolorKeywords=new HashSet<String>();
	static
	{
		CSScolorKeywords.add("aqua");
		CSScolorKeywords.add("black");
		CSScolorKeywords.add("blue");
		CSScolorKeywords.add("fuchsia");
		CSScolorKeywords.add("gray");
		CSScolorKeywords.add("green");
		CSScolorKeywords.add("lime");
		CSScolorKeywords.add("maroon");
		CSScolorKeywords.add("navy");
		CSScolorKeywords.add("olive");
		CSScolorKeywords.add("orange");
		CSScolorKeywords.add("purple");
		CSScolorKeywords.add("red");
		CSScolorKeywords.add("silver");
		CSScolorKeywords.add("teal");
		CSScolorKeywords.add("white");
		CSScolorKeywords.add("yellow");
	}
	public static boolean isValidCSSShape(String value)
	{
		if(value.indexOf("rect(")==0 && value.indexOf(")")==value.length()-1)
		{
			String[] shapeParts=value.substring(5,value.length()-1).split(",");
			if(shapeParts.length==4)
			{
				boolean isValidShapeParts=true;
				int temp;
				for(int i=0;i<shapeParts.length && isValidShapeParts;i++)
				{
					try
					{
						temp=Integer.parseInt(shapeParts[i]);
						if(temp<0)
							isValidShapeParts=false;
					}
					catch(Exception e)
					{
						isValidShapeParts=false;
					}
				}
				if(isValidShapeParts)
					return true;
			}
		}
		return false;

	}
	private final static HashSet<String> cssMedia = new HashSet<String>();
	static {
		for(String s : new String[] {"all","braille","embossed","handheld","print","projection","screen","speech","tty","tv"})
			cssMedia.add(s);
	}
	public static boolean isMedia(String media) {
		return cssMedia.contains(media);
	}
	public static boolean isColor(String value,boolean isSVG)
	{
		value=value.trim();
		if(isSVG)
		{
		if(SVGcolorKeywords.contains(value))
			return true;
		}
		else
		{
			if(CSScolorKeywords.contains(value))
				return true;
		}
		if(value.indexOf('#')==0)
		{

			if(value.length()==4)
			{
				try{
					Integer.valueOf(value.substring(1,2),16).intValue();
					Integer.valueOf(value.substring(2,3),16).intValue();
					Integer.valueOf(value.substring(3,4),16).intValue();
					return true;
				}
				catch(Exception e)
				{
				}

			}
			else if(value.length()==7)
			{

				try{
					Integer.valueOf(value.substring(1,3),16).intValue();
					Integer.valueOf(value.substring(3,5),16).intValue();
					Integer.valueOf(value.substring(5,7),16).intValue();
					return true;
				}
				catch(Exception e)
				{
				}
			}
		}
		if(value.indexOf("rgb(")==0 && value.indexOf(")")==value.length()-1)
		{
			String[] colorParts=value.substring(4,value.length()-1).split(",");
			if(colorParts.length!=3)
				return false;
			boolean isValidColorParts=true;;
			for(int i=0; i<colorParts.length && isValidColorParts;i++)
			{
				colorParts[i]=colorParts[i].trim();
				try
				{
					if(colorParts[i].indexOf('%')==colorParts[i].length()-1)
					{
						int intPart=Integer.parseInt(colorParts[i].substring(0,colorParts[i].length()-1));
						if(!(intPart>=0 && intPart<=100))
							isValidColorParts=false;
					}
					else
					{
						int intPart=Integer.parseInt(colorParts[i]);
						if(!(intPart>=0 && intPart<=255))
							isValidColorParts=false;
					}

				}
				catch(Exception e)
				{
					isValidColorParts=false;
				}

			}
			if(isValidColorParts)
				return true;
		}
		return false;
	}
	public static boolean isFrequency(String value)
	{
		String firstPart;
		value=value.trim().toLowerCase();
		boolean isValidFrequency=true;
		if(value.indexOf("khz")!=-1)
		{
			int index=value.indexOf("khz");
			firstPart=value.substring(0,index).trim();
			if(!("khz".equals(value.substring(index,value.length()).trim())))
			{
				isValidFrequency=false;
			}

		}
		else if(value.indexOf("hz")!=-1)
		{
			int index=value.indexOf("hz");
			firstPart=value.substring(0,index).trim();
			if(!("hz".equals(value.substring(index,value.length()).trim())))
			{
				isValidFrequency=false;
			}

		}
		else
			firstPart=value.trim();
		if(isValidFrequency)
		{
			try
			{
				float temp=Float.parseFloat(firstPart);
				if(temp>0)
					return true;
			}
			catch(Exception e)
			{
			}
		}
		return false;
	}
	public static boolean isTime(String value)
	{
		value=value.toLowerCase();
		String intValue;
		if(value.indexOf("ms")>-1 && value.length()>2)
			intValue=value.substring(0,value.length()-2);
		else if(value.indexOf("s")>-1 && value.length()>1)
			intValue=value.substring(0,value.length()-1);
		else
			return false;
		return isNumber(intValue);
	}
	public static String[] removeWhiteSpace(String[] values)
	{
		if(values==null) return null;
		ArrayList<String> arrayToReturn=new ArrayList<String>();
		for(String value:values)
		{
			if(value!=null && !("".equals(value.trim())))
				arrayToReturn.add(CSSTokenizerFilter.removeQuotes(value.trim()).trim());
		}
		return arrayToReturn.toArray(new String[0]);
	}
	public static String sanitizeURI(FilterCallback cb,String URI)
	{
		try
		{
		return cb.processURI(URI, null);
		}
		catch(Exception e)
		{
			return "";
		}
	}
	public static boolean isURI(FilterCallback cb,String URI)
	{
		return URI.equals(sanitizeURI(cb,URI));
	}
	public static String[] splitOnCharArray(String value,char[] splitOn)
	{
		ArrayList<String> pointPairs=new ArrayList<String>();
		//Creating HashMap for faster search operation
		HashSet<Character> charToSplit=new HashSet<Character>();
		int i;
		for(i=0;i<splitOn.length;i++)
			charToSplit.add(splitOn[i]);
		int prev=0;
		for(i=0;i<value.length();i++)
		{
			if(charToSplit.contains(value.charAt(i)))
			{
				pointPairs.add(value.substring(prev,i));
				while(i<value.length() && charToSplit.contains(value.charAt(i)))
				{
					i++;
				}
				prev=i;
				i--;
			}
		}
		boolean isLastElement=false;
		for(i=prev;i<value.length();i++)
		{
			if(!charToSplit.contains(value.charAt(i)))
			{
				isLastElement=true;
				break;
			}
		}
		if(isLastElement)
		{
			pointPairs.add(value.substring(prev,value.length()));
		}
		return pointPairs.toArray(new String[0]);
	}
	public static boolean isPointPair(String value)
	{
		char[] whiteSpaces=new char[]{' ','\n','\t'};
		System.out.println("isPointPair is calld with value="+value);
		String[] pointPairs=splitOnCharArray(value,whiteSpaces);
		for(int i=0;i<pointPairs.length;i++)
		{
			String[] strParts=pointPairs[i].split(",");
			if(strParts.length!=2)
				return false;
			try
			{
				Float.parseFloat(strParts[0]);
				Float.parseFloat(strParts[1]);
			}
			catch(Exception e)
			{
				return false;
			}
		}
		return true;
	}
//	public static HTMLNode getHTMLNodeFromElement(Element node)
//	{
//		String[] propertyName=new String[node.getAttributes().size()];
//		String[] propertyValue=new String[node.getAttributes().size()];
//		int index=0;
//		List<Attribute> attrList=node.getAttributes();
//		for(Attribute currentAttr:attrList)
//		{
//			propertyName[index]=currentAttr.getName();
//			propertyValue[index]=currentAttr.getValue();
//		}
//		return new HTMLNode(node.getName(),propertyName,propertyValue,node.getValue());
//	}
}
