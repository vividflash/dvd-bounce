/*
 * Copyright (c) 2026, vividflash
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.vividflash.dvdbounce;

import com.google.inject.Provides;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
    name = "DVD Bounce"
)
public class DvdBouncePlugin extends Plugin
{
    /**
     * Custom images are downscaled to at most this many pixels on their longest
     * side before use, so the per-bounce colour-shift pass stays cheap even when
     * the user points the plugin at a full-size photo.
     */
    private static final int MAX_SOURCE_DIMENSION = 512;

    @Inject
    private DvdBounceConfig config;

    @Inject
    private DvdBounceOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    private BufferedImage bundledPlaceholder;

    /**
     * Cache of the last custom image loaded, keyed by its path, so the file is
     * read from disk only when the configured path changes.
     */
    private String cachedCustomPath;
    private BufferedImage cachedCustomImage;

    @Override
    protected void startUp()
    {
        bundledPlaceholder = loadBundledImage("placeholder.png");
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        cachedCustomPath = null;
        cachedCustomImage = null;
    }

    @Provides
    DvdBounceConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DvdBounceConfig.class);
    }

    /**
     * Resolve the image to bounce: the custom image if configured and loadable,
     * otherwise the bundled placeholder. Called from the overlay every frame;
     * disk I/O only happens when the configured path changes.
     */
    BufferedImage resolveSourceImage()
    {
        String customPath = config.customImagePath();
        if (customPath == null || customPath.trim().isEmpty())
        {
            return bundledPlaceholder;
        }

        String path = customPath.trim();
        if (path.equals(cachedCustomPath))
        {
            return cachedCustomImage != null ? cachedCustomImage : bundledPlaceholder;
        }

        // Remember the attempted path even on failure so a broken path is not
        // re-read from disk every frame.
        cachedCustomPath = path;
        cachedCustomImage = null;
        try
        {
            File imageFile = new File(path);
            if (imageFile.isFile())
            {
                BufferedImage loaded = ImageIO.read(imageFile);
                if (loaded != null)
                {
                    cachedCustomImage = downscale(loaded);
                    return cachedCustomImage;
                }
            }
            log.warn("Could not load custom image, falling back to placeholder: {}", path);
        }
        catch (IOException e)
        {
            log.warn("Failed to read custom image, falling back to placeholder: {}", path, e);
        }
        return bundledPlaceholder;
    }

    private static BufferedImage downscale(BufferedImage source)
    {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= MAX_SOURCE_DIMENSION)
        {
            return source;
        }

        double scale = (double) MAX_SOURCE_DIMENSION / longest;
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    private BufferedImage loadBundledImage(String resource)
    {
        try (InputStream in = getClass().getResourceAsStream(resource))
        {
            if (in == null)
            {
                log.warn("Bundled {} resource not found on classpath", resource);
                return null;
            }
            return ImageIO.read(in);
        }
        catch (IOException e)
        {
            log.warn("Failed to load bundled {}", resource, e);
            return null;
        }
    }
}
