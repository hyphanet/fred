package freenet.clients.http;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;

import javax.imageio.ImageIO;

import freenet.client.HighLevelSimpleClient;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

public class ImageCreatorToadlet extends Toadlet {

	public static final int	DEFAULT_WIDTH	= 100;

	public static final int	DEFAULT_HEIGHT	= 100;

	protected ImageCreatorToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String text = req.getParam("text");
		int requiredWidth = req.getParam("width").compareTo("") != 0 ? Integer.parseInt(req.getParam("width")) : DEFAULT_WIDTH;
		int requiredHeight = req.getParam("height").compareTo("") != 0 ? Integer.parseInt(req.getParam("height")) : DEFAULT_HEIGHT;
		BufferedImage buffer = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = buffer.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		FontRenderContext fc = g2.getFontRenderContext();
		float size=1;
		g2.getFont().deriveFont(size);
		int width = 0;
		int height = 0;
		while (width < requiredWidth && height < requiredHeight) {
			Rectangle2D bounds = g2.getFont().getStringBounds(text, fc);

			// calculate the size of the text
			width = (int) bounds.getWidth();
			height = (int) bounds.getHeight();
			g2.setFont(g2.getFont().deriveFont(size+++1));
		}
		g2.setFont(g2.getFont().deriveFont(size-1));
		Rectangle2D bounds = g2.getFont().getStringBounds(text, fc);

		// prepare some output
		buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		g2 = buffer.createGraphics();
		g2.setFont(g2.getFont().deriveFont(size-1));
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// actually do the drawing
		g2.setColor(new Color(0, 0, 0));
		g2.fillRect(0, 0, width, height);
		g2.setColor(new Color(255, 255, 255));
		g2.drawString(text, 0, (int)-bounds.getY());

		double scale;
		double scaleX = (double) requiredWidth / width;
		double scaleY = (double) requiredHeight / height;
		scale = Math.min(scaleX, scaleY);

		BufferedImage scaledImage = new BufferedImage(requiredWidth, requiredHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics2D = scaledImage.createGraphics();
		AffineTransform xform = AffineTransform.getScaleInstance(scale, scale);
		graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		graphics2D.drawImage(buffer, xform, null);
		graphics2D.dispose();

		Bucket data = ctx.getBucketFactory().makeBucket(requiredWidth*requiredHeight);
		ImageIO.write(scaledImage, "png", data.getOutputStream());
		writeReply(ctx, 200, "image/png", "OK", data);
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
