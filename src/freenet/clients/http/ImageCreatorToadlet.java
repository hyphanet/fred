package freenet.clients.http;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.sql.Date;
import java.text.ParseException;

import javax.imageio.ImageIO;

import freenet.client.HighLevelSimpleClient;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

public class ImageCreatorToadlet extends Toadlet {

	public static final int		DEFAULT_WIDTH	= 100;

	public static final int		DEFAULT_HEIGHT	= 100;

	public static final Date	LAST_MODIFIED	= new Date(1248256659000l);

	protected ImageCreatorToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		boolean needsGeneration = true;
		if (ctx.getHeaders().containsKey("if-modified-since")) {
			try {
				if (ToadletContextImpl.parseHTTPDate(ctx.getHeaders().get("if-modified-since")).compareTo(LAST_MODIFIED) == 0) {
					ctx.sendReplyHeaders(304, "Not Modified", null, "image/png", 0, LAST_MODIFIED);
					needsGeneration = false;
				}
			} catch (ParseException pe) {
			}
		}
		if (needsGeneration) {
			String text = req.getParam("text");
			int requiredWidth = req.getParam("width").compareTo("") != 0 ? Integer.parseInt(req.getParam("width")) : DEFAULT_WIDTH;
			int requiredHeight = req.getParam("height").compareTo("") != 0 ? Integer.parseInt(req.getParam("height")) : DEFAULT_HEIGHT;
			BufferedImage buffer = new BufferedImage(requiredWidth, requiredHeight, BufferedImage.TYPE_INT_RGB);
			Graphics2D g2 = buffer.createGraphics();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			FontRenderContext fc = g2.getFontRenderContext();
			float size = 1;
			g2.setFont(g2.getFont().deriveFont(size));
			int width = 0;
			int height = 0;
			while (width < requiredWidth && height < requiredHeight) {
				Rectangle2D bounds = g2.getFont().getStringBounds(text, fc);

				// calculate the size of the text
				width = (int) bounds.getWidth();
				height = (int) bounds.getHeight();
				g2.setFont(g2.getFont().deriveFont(++size));
			}

			// prepare some output
			g2.setFont(g2.getFont().deriveFont(size - 1));
			Rectangle2D bounds = g2.getFont().getStringBounds(text, fc);
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			// actually do the drawing
			g2.setColor(new Color(0, 0, 0));
			g2.fillRect(0, 0, width, height);
			g2.setColor(new Color(255, 255, 255));
			g2.drawString(text, (int) (requiredWidth / 2 - bounds.getWidth() / 2), (int) (requiredHeight / 2 + bounds.getHeight() / 4));

			Bucket data = ctx.getBucketFactory().makeBucket(-1);
			ImageIO.write(buffer, "png", data.getOutputStream());
			ctx.sendReplyHeaders(200, "OK", null, "image/png", data.size(), LAST_MODIFIED);
			ctx.writeData(data);
		}
	}

	@Override
	public String path() {
		return "/imagecreator/";
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

}
