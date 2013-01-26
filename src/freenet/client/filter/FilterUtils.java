package freenet.client.filter;

import freenet.support.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

public class FilterUtils {
	private static volatile boolean logDEBUG;
	static {
	    Logger.registerClass(FilterUtils.class);
	}

	//Basic Data types
	public static boolean isInteger(String strValue)
	{
		try
		{
			Integer.parseInt(strValue);
			return true;
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
		if (value.length()>=2 && value.charAt(value.length()-1)=='%') //Valid percentage X%
		{
			// Percentages are <number>%
			// That means they can be positive, negative, zero, >100%, and they can contain decimal points.
			try
			{
				Integer.parseInt(value.substring(0,value.length()-1));
				return true;
			}
			catch(Exception e) { }
			try
			{
				Double.parseDouble(value.substring(0,value.length()-1));
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
		boolean units = false;
		if(lengthValue==null && value.length()>2) //Valid unit Vxx where xx is unit or V
		{
			String unit=value.substring(value.length()-2, value.length());
			if(allowedUnits.contains(unit)) {
				lengthValue=value.substring(0,value.length()-2);
				units = true;
			} else
				lengthValue=value.substring(0,value.length());
		}
		else
			lengthValue=value.substring(0,value.length());
		try
		{
			int x = Integer.parseInt(lengthValue);
			if(!units && !isSVG && x != 0) return false;
			return true;
		}
		catch(Exception e){ }
		try
		{
			double dval=Double.parseDouble(lengthValue);
			if(!units && !isSVG && dval != 0) return false;
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
		// as of CSS3 this is valid: http://www.w3.org/TR/css3-color/#transparent-def
		CSScolorKeywords.add("transparent");
	}
	private final static HashSet<String> CSSsystemColorKeywords=new HashSet<String>();
	static {
		CSScolorKeywords.add("ActiveBorder");
		CSScolorKeywords.add("ActiveCaption");
		CSScolorKeywords.add("AppWorkspace");
		CSScolorKeywords.add("Background");
		CSScolorKeywords.add("ButtonFace");
		CSScolorKeywords.add("ButtonHighlight");
		CSScolorKeywords.add("ButtonShadow");
		CSScolorKeywords.add("ButtonText");
		CSScolorKeywords.add("CaptionText");
		CSScolorKeywords.add("GrayText");
		CSScolorKeywords.add("Highlight");
		CSScolorKeywords.add("HighlightText");
		CSScolorKeywords.add("InactiveBorder");
		CSScolorKeywords.add("InactiveCaption");
		CSScolorKeywords.add("InactiveCaptionText");
		CSScolorKeywords.add("InfoBackground");
		CSScolorKeywords.add("InfoText");
		CSScolorKeywords.add("Menu");
		CSScolorKeywords.add("MenuText");
		CSScolorKeywords.add("Scrollbar");
		CSScolorKeywords.add("ThreeDDarkShadow");
		CSScolorKeywords.add("ThreeDFace");
		CSScolorKeywords.add("ThreeDHighlight");
		CSScolorKeywords.add("ThreeDLightShadow");
		CSScolorKeywords.add("ThreeDShadow");
		CSScolorKeywords.add("Window");
		CSScolorKeywords.add("WindowFrame");
		CSScolorKeywords.add("WindowText");
	}
	public static boolean isValidCSSShape(String value)
	{
		if(value.indexOf("rect(")==0 && value.indexOf(")")==value.length()-1)
		{
			String[] shapeParts=value.substring(5,value.length()-1).split(",");
			if(shapeParts.length==4)
			{
				for(String s : shapeParts)
				{
					s = s.trim();
					if(!(s.equalsIgnoreCase("auto") || isLength(s, false)))
						return false;
				}
				return true;
			}
		}
		return false;

	}
	private final static HashSet<String> cssMedia = new HashSet<String>();
	static {
	    cssMedia.addAll(Arrays.asList(new String[]{"all", "aural", "braille", "embossed", "handheld", "print", "projection", "screen", "speech", "tty", "tv"}));
	}
	public static boolean isMedia(String media) {
		return cssMedia.contains(media);
	}
	public static boolean isColor(String value)
	{
		value=value.trim();

		if(CSScolorKeywords.contains(value) || CSSsystemColorKeywords.contains(value) || SVGcolorKeywords.contains(value))
			return true;

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
			boolean isValidColorParts=true;
			for(int i=0; i<colorParts.length && isValidColorParts;i++)
			{
				if(!(isPercentage(colorParts[i].trim()) || isInteger(colorParts[i].trim())))
					isValidColorParts = false;
			}
			if(isValidColorParts)
				return true;
		}
		if(value.indexOf("rgba(")==0 && value.indexOf(")")==value.length()-1)
		{
			String[] colorParts=value.substring(5,value.length()-1).split(",");
			if(colorParts.length!=4)
				return false;
			boolean isValidColorParts=true;
			for(int i=0; i<colorParts.length-1 && isValidColorParts;i++)
			{
				if(!(isPercentage(colorParts[i].trim()) || isInteger(colorParts[i].trim())))
					isValidColorParts = false;
			}
			if(isValidColorParts && isNumber(colorParts[3]))
				return true;
		}

		if(value.indexOf("hsl(")==0 && value.indexOf(")")==value.length()-1)
		{
			String[] colorParts = value.substring(4, value.length() - 1).split(",");
			if (colorParts.length != 3) {
			    return false;
			}

			if(isNumber(colorParts[0]) && isPercentage(colorParts[1]) && isPercentage(colorParts[2]))
			    return true;
		}

		if(value.indexOf("hsla(")==0 && value.indexOf(")")==value.length()-1)
		{
			String[] colorParts = value.substring(5, value.length() - 1).split(",");
			if (colorParts.length != 4) {
			    return false;
			}

			if(isNumber(colorParts[0]) && isPercentage(colorParts[1]) && isPercentage(colorParts[2]) && isNumber(colorParts[3]))
			    return true;
		}

		return false;
	}
	
	public static boolean isCSSTransform(String value) {
	    value = value.trim();
	    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform(\""+value+"\")");
	    
	    if(value.indexOf("matrix(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String[] parts = value.substring(7, value.length() - 1).split(",");
		if (parts.length != 6) {
		    return false;
		}

		boolean isValid = true;
		for (int i = 0; i < parts.length && isValid; i++) {
		    if (!isNumber(parts[i].trim())) {
			isValid = false;
		    }
		}
		if (isValid) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a matrix()");
		    return true;
		}
	    }

	    if(value.indexOf("translateX(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String part = value.substring(11, value.length() - 1);
		if (isPercentage(part.trim()) || isLength(part.trim(), false)) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a translateX()");
		    return true;
		}
	    }

	    if(value.indexOf("translateY(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String part = value.substring(11, value.length() - 1);
		if (isPercentage(part.trim()) || isLength(part.trim(), false)) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a translateY()");
		    return true;
		}
	    }

	    if(value.indexOf("translate(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String[] parts = value.substring(10, value.length() - 1).split(",");
		if (parts.length == 1 && (isPercentage(parts[0].trim()) || isLength(parts[0].trim(), false))) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a translate()");
		    return true;
		}else if (parts.length == 2 && (isPercentage(parts[0].trim()) || isLength(parts[0].trim(), false)) && (isPercentage(parts[1].trim()) || isLength(parts[1].trim(), false))) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a translate()");
		    return true;
		}
	    }

	    if(value.indexOf("scale(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String[] parts = value.substring(6, value.length() - 1).split(",");
		if (parts.length == 1 && isNumber(parts[0].trim())) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a scale()");
		    return true;
		}else if (parts.length == 2 && isNumber(parts[0].trim()) && isNumber(parts[1].trim())) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a scale()");
		    return true;
		}
	    }
	    
	    if(value.indexOf("scaleX(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String part = value.substring(7, value.length() - 1);
		if (isNumber(part.trim())) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a scaleX()");
		    return true;
		}
	    }

	    if(value.indexOf("scaleY(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String part = value.substring(7, value.length() - 1);
		if (isNumber(part.trim())) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a scaleY()");
		    return true;
		}
	    }

	    if(value.indexOf("rotate(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String part = value.substring(7, value.length() - 1);
		if (isAngle(part.trim())) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a rotate()");
		    return true;
		}
	    }

	    if(value.indexOf("skewX(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String part = value.substring(6, value.length() - 1);
		if (isNumber(part.trim()) || isAngle(part.trim())) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a skewX()");
		    return true;
		}
	    }

	    if(value.indexOf("skewY(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String part = value.substring(6, value.length() - 1);
		if (isNumber(part.trim()) || isAngle(part.trim())) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a skewY()");
		    return true;
		}
	    }

	    if(value.indexOf("skew(")==0 && value.indexOf(")")==value.length()-1)
	    {
		String[] parts = value.substring(5, value.length() - 1).split(",");
		if (parts.length == 1 && (isNumber(parts[0].trim()) || isAngle(parts[0].trim()))) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a skew()");
		    return true;
		}else if (parts.length == 2 && (isNumber(parts[0].trim()) || isAngle(parts[0].trim())) && (isNumber(parts[1].trim()) || isAngle(parts[0].trim()))) {
		    if(logDEBUG) Logger.debug(FilterUtils.class, "isCSSTransform found a skew()");
		    return true;
		}
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
	public static String[] removeWhiteSpace(String[] values, boolean stripQuotes)
	{
		if(values==null) return null;
		ArrayList<String> arrayToReturn=new ArrayList<String>();
		for(String value:values)
		{
			value = value.trim();
			if(stripQuotes)
				value = CSSTokenizerFilter.removeOuterQuotes(value).trim();
			if(value!=null && !("".equals(value.trim())))
				arrayToReturn.add(value);
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
	public static String[] splitOnCharArray(String value,String splitOn)
	{
		ArrayList<String> pointPairs=new ArrayList<String>();
		//Creating HashMap for faster search operation
		int i;
		int prev=0;
		for(i=0;i<value.length();i++)
		{
			if(splitOn.indexOf(value.charAt(i))!=-1)
			{
				pointPairs.add(value.substring(prev,i));
				while(i<value.length() && splitOn.indexOf(value.charAt(i))!=-1)
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
			if(splitOn.indexOf(value.charAt(i))==-1)
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
		String[] pointPairs=splitOnCharArray(value," \n\t");
		for(String pointPair: pointPairs)
		{
			String[] strParts=pointPair.split(",");
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
