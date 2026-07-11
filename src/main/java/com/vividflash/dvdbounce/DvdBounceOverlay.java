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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

@Singleton
public class DvdBounceOverlay extends Overlay
{
    /**
     * Hue step per bounce, as a fraction of a full turn (~47 degrees — close to
     * the colour change of the classic DVD screensaver).
     */
    private static final float HUE_STEP = 47f / 360f;

    private static final long CORNER_FLASH_DURATION_MS = 400L;

    /**
     * Frame-time clamp so motion stays smooth while the client throttles its
     * frame rate when the window is unfocused or covered.
     */
    private static final double MAX_FRAME_SECONDS = 0.25;

    /**
     * Frame gaps longer than this are a stall (world hop, client freeze,
     * laptop resume): motion pauses for that gap instead of lurching ahead
     * while the client catches up.
     */
    private static final double STALL_SECONDS = 0.5;

    private final Client client;
    private final DvdBouncePlugin plugin;
    private final DvdBounceConfig config;

    private double x;
    private double y;
    private double directionX = 1;
    private double directionY = 1;
    private long lastFrameNanos;
    private boolean positionInitialized;

    private int bounceCount;
    private long cornerFlashStartMs;

    /**
     * Tinted copies of the source frames for the current bounce count, so an
     * animated source is hue-rotated once per frame per bounce instead of on
     * every frame swap.
     */
    private final Map<BufferedImage, BufferedImage> tintedFrames = new HashMap<>();
    private int tintedBounceCount = -1;

    @Inject
    DvdBounceOverlay(Client client, DvdBouncePlugin plugin, DvdBounceConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (client.getCanvas() == null)
        {
            return null;
        }

        // In stretched mode overlays draw on the pre-stretch surface, whose size
        // is the real dimensions — the AWT canvas is the post-stretch window.
        Dimension canvas = client.isStretchedEnabled()
            ? client.getRealDimensions()
            : client.getCanvas().getSize();
        int canvasWidth = canvas.width;
        int canvasHeight = canvas.height;
        if (canvasWidth <= 0 || canvasHeight <= 0)
        {
            return null;
        }

        AnimatedImage source = plugin.resolveSourceImage();
        if (source == null)
        {
            return null;
        }

        int drawWidth = Math.min(config.imageSize(), canvasWidth);
        int drawHeight = Math.max(1,
            (int) Math.round((double) drawWidth * source.getHeight() / source.getWidth()));
        drawHeight = Math.min(drawHeight, canvasHeight);

        advancePosition(canvasWidth - drawWidth, canvasHeight - drawHeight);

        // Animated sources loop on the wall clock; static ones always return
        // their single frame.
        BufferedImage frame = source.frameAt(System.currentTimeMillis());
        BufferedImage image = config.colourShift() ? tintedFor(frame) : frame;
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, (int) Math.round(x), (int) Math.round(y),
            drawWidth, drawHeight, null);

        renderCornerFlash(graphics, canvasWidth, canvasHeight);

        return new Dimension(canvasWidth, canvasHeight);
    }

    /**
     * Move the image along its 45-degree path and reflect it off the travel-area
     * edges, integrating frame-by-frame so motion stays continuous through
     * canvas resizes and live config changes.
     */
    private void advancePosition(int travelWidth, int travelHeight)
    {
        long now = System.nanoTime();
        double raw = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1e9;
        double dt = raw > STALL_SECONDS ? 0 : Math.min(raw, MAX_FRAME_SECONDS);
        lastFrameNanos = now;

        travelWidth = Math.max(0, travelWidth);
        travelHeight = Math.max(0, travelHeight);

        if (!positionInitialized)
        {
            x = travelWidth * 0.31;
            y = travelHeight * 0.73;
            positionInitialized = true;
        }

        double step = config.speed() * dt;
        x += directionX * step;
        y += directionY * step;

        boolean bouncedX = false;
        boolean bouncedY = false;
        if (x <= 0)
        {
            x = 0;
            directionX = 1;
            bouncedX = true;
        }
        else if (x >= travelWidth)
        {
            x = travelWidth;
            directionX = -1;
            bouncedX = true;
        }
        if (y <= 0)
        {
            y = 0;
            directionY = 1;
            bouncedY = true;
        }
        else if (y >= travelHeight)
        {
            y = travelHeight;
            directionY = -1;
            bouncedY = true;
        }

        // A canvas resize can pin the image against an edge for several frames;
        // only count a bounce when the image actually had room to travel.
        if (bouncedX && travelWidth > 0)
        {
            bounceCount++;
        }
        if (bouncedY && travelHeight > 0)
        {
            bounceCount++;
        }
        if (bouncedX && bouncedY && travelWidth > 0 && travelHeight > 0)
        {
            cornerFlashStartMs = System.currentTimeMillis();
        }
    }

    /**
     * The source frame with the current bounce count's hue rotation applied.
     * Cached per frame until the next bounce changes the hue; the size guard
     * sheds stale entries when the user switches to a different source image.
     */
    private BufferedImage tintedFor(BufferedImage source)
    {
        if (tintedBounceCount != bounceCount || tintedFrames.size() > 32)
        {
            tintedFrames.clear();
            tintedBounceCount = bounceCount;
        }

        float hueShift = (bounceCount * HUE_STEP) % 1f;
        return tintedFrames.computeIfAbsent(source, f -> hueRotate(f, hueShift));
    }

    private static BufferedImage hueRotate(BufferedImage source, float hueShift)
    {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = source.getRGB(0, 0, w, h, null, 0, w);
        float[] hsb = new float[3];
        for (int i = 0; i < pixels.length; i++)
        {
            int argb = pixels[i];
            int alpha = argb & 0xFF000000;
            Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
            int rgb = Color.HSBtoRGB((hsb[0] + hueShift) % 1f, hsb[1], hsb[2]);
            pixels[i] = alpha | (rgb & 0x00FFFFFF);
        }
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return out;
    }

    private void renderCornerFlash(Graphics2D graphics, int canvasWidth, int canvasHeight)
    {
        if (!config.cornerFlash() || cornerFlashStartMs == 0)
        {
            return;
        }

        long elapsed = System.currentTimeMillis() - cornerFlashStartMs;
        if (elapsed >= CORNER_FLASH_DURATION_MS)
        {
            cornerFlashStartMs = 0;
            return;
        }

        float alpha = 1f - (float) elapsed / CORNER_FLASH_DURATION_MS;
        graphics.setColor(new Color(1f, 1f, 1f, alpha));
        graphics.fillRect(0, 0, canvasWidth, canvasHeight);
    }
}
