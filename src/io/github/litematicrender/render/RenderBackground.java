package io.github.litematicrender.render;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Background pixels are present before glass/water blend onto the render target. */
public final class RenderBackground {
    private RenderBackground() {}
    public static boolean isTransparent(RenderSettings settings) {
        return settings.transparentBackground || "checker".equals(settings.backgroundMode);
    }
    public static void fill(Image target, RenderSettings settings) {
        if (isTransparent(settings)) return;
        if ("image".equals(settings.backgroundMode) && settings.backgroundImage != null) {
            Image source = settings.backgroundImage;
            double scale = Math.max((double)target.width/source.width, (double)target.height/source.height);
            int width = (int)Math.ceil(source.width*scale), height = (int)Math.ceil(source.height*scale);
            BufferedImage background = new BufferedImage(target.width,target.height,BufferedImage.TYPE_INT_ARGB);
            var graphics = background.createGraphics();
            try {
                graphics.setColor(new Color(0x22262E)); graphics.fillRect(0,0,target.width,target.height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source.toBuffered(),(target.width-width)/2,(target.height-height)/2,width,height,null);
            } finally { graphics.dispose(); }
            background.getRGB(0,0,target.width,target.height,target.pixels(),0,target.width);
        }
    }
}
