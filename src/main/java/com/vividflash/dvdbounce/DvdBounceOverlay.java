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
import java.awt.geom.AffineTransform;
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
     * Hue step per bounce, as a fraction of a full turn (47 degrees, close to
     * the colour change of the classic DVD screensaver).
     */
    private static final float HUE_STEP = 47f / 360f;

    /**
     * Steps after which the hue is back where it started. 47 and 360 share no
     * factors, so the cycle is exactly 360 steps; wrapping the step counter
     * there also keeps it clear of int overflow after a long frame gap.
     */
    private static final int HUE_CYCLE_STEPS = 360;

    /**
     * Shortest gap between two hue recomputes. A hue rotation walks every pixel
     * of the draw-size image, so when the travel distance drops below one frame
     * step and the image bounces on every frame, this caps the cost. Bounces
     * further apart than this are unaffected.
     */
    private static final long TINT_MIN_INTERVAL_MS = 50L;

    /** Draw size floor, in case a profile holds a value outside the config range. */
    private static final int MIN_DRAW_SIZE = 1;

    private final Client client;
    private final DvdBouncePlugin plugin;
    private final DvdBounceConfig config;

    private double x;
    private double y;
    private double directionX = 1;
    private double directionY = 1;
    private long lastFrameNanos;
    private boolean hasLastFrame;
    private boolean positionInitialized;

    /**
     * Hue rotation to apply, counted in {@link #HUE_STEP} units and wrapped at
     * {@link #HUE_CYCLE_STEPS}. A corner hit reflects both axes at once and so
     * advances two steps, which is what "a step per edge" works out to.
     */
    private int hueStep;

    /**
     * Smoothed frame interval (EMA), used to pick the draw mode. At the
     * standard ~60 fps the speed presets advance in whole pixels, so crisp
     * integer positions are ideal. On higher frame rates (GPU/117HD unlocked
     * fps, custom targets like 72) frames no longer align with pixel steps,
     * so the image is drawn at sub-pixel positions with bilinear filtering
     * instead. Measuring the real frame time covers every fps source without
     * reading other plugins' config; hysteresis stops the mode flapping.
     */
    private double avgFrameSeconds;
    private boolean subPixel;

    /**
     * How long after a world hop completes before motion resumes, giving the
     * client a moment to settle.
     */
    private static final long RESUME_GRACE_MS = 1200L;

    /**
     * While paused the logo freezes in place: position, animation frame and
     * hue stop advancing, so the overlay does no work beyond one cached blit.
     */
    private boolean paused;
    private long resumeAtMs = Long.MAX_VALUE;
    private long pausedClockMs;

    /**
     * Source frames pre-scaled to the current draw size, so each animation
     * frame is resized once instead of bilinearly rescaled on every render.
     */
    private final Map<BufferedImage, BufferedImage> scaledFrames = new HashMap<>();
    private AnimatedImage scaledSource;
    private int scaledWidth;
    private int scaledHeight;

    /**
     * Tinted copies of the draw-size frames for the current hue, so an animated
     * source is hue-rotated once per frame per hue change instead of on every
     * frame swap, and only over draw-size pixels.
     */
    private final Map<BufferedImage, BufferedImage> tintedFrames = new HashMap<>();
    private int tintedStep = -1;
    private long tintedAtMs;

    @Inject
    DvdBounceOverlay(Client client, DvdBouncePlugin plugin, DvdBounceConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(Overlay.PRIORITY_HIGH);
        // The overlay picks its own position every frame and reports the whole
        // client as its bounds. Leaving the drag flags on would outline the
        // entire client in overlay management mode and let a drag on empty
        // space give it a preferred location that offsets the bounce area.
        setMovable(false);
        setSnappable(false);
        setResettable(false);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Overlays draw onto the client's own raster, which is the pre-stretch
        // surface in stretched mode. Its size is the client canvas size, not
        // the AWT component size.
        int canvasWidth = client.getCanvasWidth();
        int canvasHeight = client.getCanvasHeight();
        if (canvasWidth <= 0 || canvasHeight <= 0)
        {
            return null;
        }

        AnimatedImage source = plugin.resolveSourceImage();
        if (source == null)
        {
            return null;
        }

        // The config range keeps this sane, but a hand-edited profile can hold
        // anything, and a zero or negative draw size would throw from here on.
        int requestedWidth = Math.max(MIN_DRAW_SIZE, config.imageSize());
        int drawWidth = Math.min(requestedWidth, canvasWidth);
        int drawHeight = Math.max(MIN_DRAW_SIZE,
            (int) Math.round((double) drawWidth * source.getHeight() / source.getWidth()));
        drawHeight = Math.min(drawHeight, canvasHeight);

        long nowMs = monotonicMs();
        if (paused && nowMs >= resumeAtMs)
        {
            paused = false;
            // No time accrued across the pause: motion resumes from the
            // frozen spot instead of jumping ahead.
            hasLastFrame = false;
        }

        if (paused)
        {
            hasLastFrame = false;
        }
        else
        {
            advancePosition(canvasWidth - drawWidth, canvasHeight - drawHeight);
        }

        // Animated sources loop on the monotonic clock; static ones always
        // return their single frame. While paused the clock freezes too.
        BufferedImage frame = source.frameAt(paused ? pausedClockMs : nowMs);
        BufferedImage scaled = scaledFor(source, frame, drawWidth, drawHeight);
        BufferedImage image = config.colourShift() ? tintedFor(scaled, nowMs) : scaled;

        if (useSubPixel())
        {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, AffineTransform.getTranslateInstance(x, y), null);
        }
        else
        {
            graphics.drawImage(image, (int) Math.round(x), (int) Math.round(y), null);
        }

        return new Dimension(canvasWidth, canvasHeight);
    }

    /**
     * Reset the run state that must not survive a plugin toggle: the pause
     * flags, the frame timer and the measured frame rate. The overlay is a
     * singleton, so a pause captured mid-hop would otherwise still be set on
     * the next start. Position, direction and hue are deliberately kept, so
     * the logo carries on from where it was rather than jumping.
     */
    void resetState()
    {
        paused = false;
        resumeAtMs = Long.MAX_VALUE;
        hasLastFrame = false;
        avgFrameSeconds = 0;
        subPixel = false;
    }

    /**
     * Drop the pre-scaled and tinted frame caches so a disabled plugin pins
     * no image memory. Called from the plugin's shutDown.
     */
    void clearImageCaches()
    {
        scaledFrames.clear();
        tintedFrames.clear();
        scaledSource = null;
        tintedStep = -1;
    }

    /**
     * Milliseconds from the monotonic clock. Used for the animation clock and
     * pause/resume timers instead of the wall clock, which can jump around
     * NTP corrections; the epoch is arbitrary but all users only ever compare
     * differences.
     */
    private static long monotonicMs()
    {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * The draw mode for this frame: the FPS mode config forces crisp or
     * smooth outright; Adaptive follows the measured frame rate.
     */
    private boolean useSubPixel()
    {
        switch (config.fpsMode())
        {
            case CRISP:
                return false;
            case SMOOTH:
                return true;
            default:
                return subPixel;
        }
    }

    /**
     * Track the smoothed frame rate and flip between crisp integer rendering
     * (~60 fps and below) and sub-pixel rendering (above), with hysteresis.
     */
    private void updateDrawMode(double dt)
    {
        if (dt <= 0 || dt > 0.25)
        {
            return;
        }
        avgFrameSeconds = avgFrameSeconds == 0 ? dt : avgFrameSeconds * 0.95 + dt * 0.05;
        double fps = 1.0 / avgFrameSeconds;
        if (subPixel ? fps < 63 : fps > 68)
        {
            subPixel = fps > 68;
        }
    }

    /**
     * Freeze the logo where it is. Repeated calls (hop -> login states) keep
     * it paused; the pending resume, if any, is cancelled.
     */
    void pause()
    {
        if (!paused)
        {
            paused = true;
            pausedClockMs = monotonicMs();
        }
        resumeAtMs = Long.MAX_VALUE;
    }

    /**
     * Arm the delayed resume after a completed hop/login. Only the first
     * LOGGED_IN after a pause arms it; harmless no-op while unpaused.
     */
    void scheduleResume()
    {
        if (paused && resumeAtMs == Long.MAX_VALUE)
        {
            resumeAtMs = monotonicMs() + RESUME_GRACE_MS;
        }
    }

    /**
     * Move the image along its 45-degree path, folding the traveled distance
     * exactly into the reflected path. A gap between frames, from a world hop,
     * a client freeze or a laptop resume, puts the image where it would be had
     * it kept moving the whole time, bounces included.
     */
    private void advancePosition(int travelWidth, int travelHeight)
    {
        long now = System.nanoTime();
        // nanoTime is monotonic within a JVM, but the max() means a backwards
        // step could never drive the position negative.
        double dt = hasLastFrame ? Math.max(0, (now - lastFrameNanos) / 1e9) : 0;
        lastFrameNanos = now;
        hasLastFrame = true;

        travelWidth = Math.max(0, travelWidth);
        travelHeight = Math.max(0, travelHeight);

        if (!positionInitialized)
        {
            // Fixed fractions rather than a random spot: the start is well
            // clear of the edges and repeats across sessions, so the first
            // bounce is never immediate and the motion is reproducible.
            x = travelWidth * 0.31;
            y = travelHeight * 0.73;
            positionInitialized = true;
        }

        updateDrawMode(dt);

        double step = config.bounceSpeed().getPixelsPerSecond() * dt;

        double[] state = {x, directionX};
        int bouncesX = fold(state, travelWidth, step);
        x = state[0];
        directionX = state[1];

        state[0] = y;
        state[1] = directionY;
        int bouncesY = fold(state, travelHeight, step);
        y = state[0];
        directionY = state[1];

        hueStep = (int) ((hueStep + (long) bouncesX + bouncesY) % HUE_CYCLE_STEPS);
    }

    /**
     * Advance one axis by {@code step} along its edge-reflected path.
     * {@code state} holds {position, direction} and is updated in place;
     * returns how many edge bounces the step crossed. The reflected path is a
     * triangle wave with period {@code 2 * travel}, so any distance folds in
     * exactly regardless of how many reflections it spans.
     */
    private static int fold(double[] state, double travel, double step)
    {
        if (travel <= 0)
        {
            // No room to travel (image as large as the canvas, or a resize
            // squeeze): pin to the edge without counting bounces.
            state[0] = 0;
            return 0;
        }

        double pos = Math.max(0, Math.min(state[0], travel));
        // Phase runs monotonically along the unfolded path: 0..travel is the
        // outbound leg, travel..2*travel the return leg.
        double phase = state[1] >= 0 ? pos : 2 * travel - pos;
        double advanced = phase + step;
        int bounces = (int) Math.min(Integer.MAX_VALUE,
            Math.floor(advanced / travel) - Math.floor(phase / travel));

        double m = advanced % (2 * travel);
        if (m <= travel)
        {
            state[0] = m;
            state[1] = 1;
        }
        else
        {
            state[0] = 2 * travel - m;
            state[1] = -1;
        }
        return Math.max(0, bounces);
    }

    /**
     * The given frame pre-scaled to the current draw size, computed once per
     * frame and cached until the source image or draw size changes. The tint
     * cache is keyed by these scaled frames, so it resets alongside.
     */
    private BufferedImage scaledFor(AnimatedImage source, BufferedImage frame, int width, int height)
    {
        if (scaledSource != source || scaledWidth != width || scaledHeight != height
            || scaledFrames.size() > 32)
        {
            scaledFrames.clear();
            tintedFrames.clear();
            scaledSource = source;
            scaledWidth = width;
            scaledHeight = height;
        }

        return scaledFrames.computeIfAbsent(frame, f ->
        {
            if (f.getWidth() == width && f.getHeight() == height)
            {
                return f;
            }
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(f, 0, 0, width, height, null);
            g.dispose();
            return scaled;
        });
    }

    /**
     * The draw-size frame with the current hue rotation applied. The cache is
     * dropped when the hue moves on, but no more often than
     * {@link #TINT_MIN_INTERVAL_MS}, so a bounce on every frame cannot make
     * this walk the image pixels 60 times a second. A negative
     * {@link #tintedStep} means no tint is cached yet and the interval does
     * not apply, since the monotonic clock's epoch is arbitrary.
     */
    private BufferedImage tintedFor(BufferedImage source, long nowMs)
    {
        if (tintedStep != hueStep
            && (tintedStep < 0 || nowMs - tintedAtMs >= TINT_MIN_INTERVAL_MS))
        {
            tintedFrames.clear();
            tintedStep = hueStep;
            tintedAtMs = nowMs;
        }

        float hueShift = (tintedStep * HUE_STEP) % 1f;
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

}
