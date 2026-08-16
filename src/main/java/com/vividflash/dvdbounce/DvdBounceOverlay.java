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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
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

    /** Opacity is a percentage, and this value is fully opaque. */
    private static final int FULL_OPACITY = 100;

    /**
     * How long after a world hop completes before motion resumes, giving the
     * client a moment to settle.
     */
    private static final long RESUME_GRACE_MS = 1200L;

    /**
     * A frame this far off the smoothed interval is treated as a change of
     * frame rate rather than jitter around the current one, so it moves by its
     * own length instead of the smoothed one. The uneven frame times a cap
     * produces while holding one target land inside this band. A large change
     * such as 144 to 60 lands outside it and is followed at once; a small one
     * such as 60 to 50 is inside it and is followed over the next second of
     * frames instead.
     */
    private static final double STEADY_LOW = 0.75;
    private static final double STEADY_HIGH = 1.33;

    /**
     * Frames longer than this are a freeze, a hop or a resumed laptop, not a
     * frame rate. They move by their true length and leave the smoothed
     * interval alone.
     */
    private static final double MAX_TRACKED_SECONDS = 0.25;

    private final Client client;
    private final DvdBouncePlugin plugin;
    private final DvdBounceConfig config;

    /**
     * The two pictures. Each keeps its own position, direction, hue and image
     * caches, so they cross the screen independently, and each reads its own
     * half of the config. They start well apart and head opposite ways, so two
     * pictures of the same size never travel as one.
     */
    private final Picture itemPicture;
    private final Picture customPicture;

    private long lastFrameNanos;
    private boolean hasLastFrame;

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
     * While paused both pictures freeze in place: position, animation frame and
     * hue stop advancing, so the overlay does no work beyond the cached blits.
     */
    private boolean paused;
    private long resumeAtMs = Long.MAX_VALUE;
    private long pausedClockMs;

    @Inject
    DvdBounceOverlay(Client client, DvdBouncePlugin plugin, DvdBounceConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        this.itemPicture = new Picture(0.31, 0.73, 1, 1,
            config::itemSize, config::itemOpacity, config::itemSpeed, config::itemColourShift);
        this.customPicture = new Picture(0.68, 0.24, -1, 1,
            config::customSize, config::customOpacity, config::customSpeed,
            config::customColourShift);
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

        if (!config.itemEnabled() && !config.customEnabled())
        {
            // Nothing switched on: report no bounds rather than claiming the
            // whole client, and do no clock work at all. Dropping the frame
            // timer means the first frame after one is switched back on has no
            // accrued time, so it carries on from where it stopped.
            hasLastFrame = false;
            return null;
        }

        long nowMs = monotonicMs();
        if (paused && nowMs >= resumeAtMs)
        {
            paused = false;
            // No time accrued across the pause: motion resumes from the
            // frozen spot instead of jumping ahead.
            hasLastFrame = false;
        }

        // One clock reading for both pictures, so they never drift apart by a
        // frame, and one draw-mode decision from it.
        double dt = stepSeconds(frameSeconds());
        updateDrawMode();
        long clockMs = paused ? pausedClockMs : nowMs;
        boolean drawSubPixel = useSubPixel();

        if (config.itemEnabled())
        {
            itemPicture.render(graphics, plugin.resolveItemImage(), clockMs, nowMs, dt,
                drawSubPixel, canvasWidth, canvasHeight);
        }
        if (config.customEnabled())
        {
            customPicture.render(graphics, plugin.resolveCustomImage(), clockMs, nowMs, dt,
                drawSubPixel, canvasWidth, canvasHeight);
        }

        return new Dimension(canvasWidth, canvasHeight);
    }

    /**
     * Seconds since the previous frame, and 0 while paused or on the first
     * frame after the timer is reset. nanoTime is monotonic within a JVM, but
     * the max() means a backwards step could never drive a position negative.
     */
    private double frameSeconds()
    {
        long now = System.nanoTime();
        double dt = hasLastFrame && !paused ? Math.max(0, (now - lastFrameNanos) / 1e9) : 0;
        lastFrameNanos = now;
        hasLastFrame = !paused;
        return dt;
    }

    /**
     * Reset the run state that must not survive a plugin toggle: the pause
     * flags, the frame timer and the measured frame rate. The overlay is a
     * singleton, so a pause captured mid-hop would otherwise still be set on
     * the next start. Position, direction and hue are deliberately kept, so
     * each picture carries on from where it was rather than jumping.
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
        itemPicture.clearCaches();
        customPicture.clearCaches();
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
     * How far to move this frame, and the upkeep of the smoothed interval it
     * comes from.
     *
     * <p>While the frame rate holds, every frame moves by the smoothed interval
     * rather than by its own, so the pixel steps stay even and crisp rendering
     * has nothing uneven to round. Frame times are never exact: an fps cap
     * sleeps in whole milliseconds and the scheduler adds its own noise, and
     * with a raw interval that noise becomes visible judder at whole-pixel
     * positions.
     *
     * <p>A frame outside the steady band moves by its true length, so a real
     * gap is still covered exactly, and pulls the average halfway to itself so
     * a new frame rate is followed within a few frames rather than drifted
     * toward. That is what a foreground to background fps switch looks like.
     */
    private double stepSeconds(double dt)
    {
        if (dt <= 0 || dt > MAX_TRACKED_SECONDS)
        {
            return Math.max(0, dt);
        }
        if (avgFrameSeconds <= 0)
        {
            avgFrameSeconds = dt;
            return dt;
        }
        if (dt < avgFrameSeconds * STEADY_LOW || dt > avgFrameSeconds * STEADY_HIGH)
        {
            avgFrameSeconds = (avgFrameSeconds + dt) / 2;
            return dt;
        }
        avgFrameSeconds = avgFrameSeconds * 0.95 + dt * 0.05;
        return avgFrameSeconds;
    }

    /**
     * Flip between crisp integer rendering (~60 fps and below) and sub-pixel
     * rendering (above) from the smoothed frame rate, with hysteresis so the
     * mode does not flap around the threshold.
     */
    private void updateDrawMode()
    {
        if (avgFrameSeconds <= 0)
        {
            return;
        }
        double fps = 1.0 / avgFrameSeconds;
        if (subPixel ? fps < 63 : fps > 68)
        {
            subPixel = fps > 68;
        }
    }

    /**
     * Freeze both pictures where they are. Repeated calls (hop -> login states)
     * keep them paused; the pending resume, if any, is cancelled.
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

    /**
     * One bouncing picture: where it is, which way it is going, how far its
     * hue has turned, and the frames it has prepared at the current draw size.
     * The settings it reads come in as suppliers, so the same class serves the
     * item and the custom image without knowing which it is drawing.
     */
    private static final class Picture
    {
        private final double startFractionX;
        private final double startFractionY;
        private final IntSupplier size;
        private final IntSupplier opacity;
        private final Supplier<BounceSpeed> speed;
        private final BooleanSupplier colourShift;

        private double x;
        private double y;
        private double directionX;
        private double directionY;
        private boolean positionInitialized;

        /**
         * Hue rotation to apply, counted in {@link #HUE_STEP} units and wrapped
         * at {@link #HUE_CYCLE_STEPS}. A corner hit reflects both axes at once
         * and so advances two steps, which is what "a step per edge" works out
         * to.
         */
        private int hueStep;

        /**
         * Source frames pre-scaled to the current draw size and carrying the
         * configured opacity, so each animation frame is resized and faded once
         * instead of on every render.
         */
        private final Map<BufferedImage, BufferedImage> scaledFrames = new HashMap<>();
        private AnimatedImage scaledSource;
        private int scaledWidth;
        private int scaledHeight;
        private int scaledOpacity = FULL_OPACITY;

        /**
         * Tinted copies of the draw-size frames for the current hue, so an
         * animated source is hue-rotated once per hue change instead of on
         * every frame swap, and only over draw-size pixels.
         */
        private final Map<BufferedImage, BufferedImage> tintedFrames = new HashMap<>();
        private int tintedStep = -1;
        private long tintedAtMs;

        private Picture(double startFractionX, double startFractionY, int directionX,
            int directionY, IntSupplier size, IntSupplier opacity, Supplier<BounceSpeed> speed,
            BooleanSupplier colourShift)
        {
            this.startFractionX = startFractionX;
            this.startFractionY = startFractionY;
            this.directionX = directionX;
            this.directionY = directionY;
            this.size = size;
            this.opacity = opacity;
            this.speed = speed;
            this.colourShift = colourShift;
        }

        private void render(Graphics2D graphics, AnimatedImage source, long clockMs, long nowMs,
            double dt, boolean drawSubPixel, int canvasWidth, int canvasHeight)
        {
            if (source == null)
            {
                return;
            }

            // The config range keeps this sane, but a hand-edited profile can
            // hold anything, and a zero or negative draw size would throw from
            // here on.
            int requestedWidth = Math.max(MIN_DRAW_SIZE, size.getAsInt());
            int drawWidth = Math.min(requestedWidth, canvasWidth);
            int drawHeight = Math.max(MIN_DRAW_SIZE,
                (int) Math.round((double) drawWidth * source.getHeight() / source.getWidth()));
            drawHeight = Math.min(drawHeight, canvasHeight);

            // What travels is the visible part of the picture, not its box. An
            // item sprite is padded out to 36x32, and bouncing the box would
            // turn it around short of the edge, by more pixels the larger it is
            // drawn. The padding is allowed off the edge instead, so nothing is
            // trimmed and the picture itself is untouched.
            double scaleX = (double) drawWidth / source.getWidth();
            double scaleY = (double) drawHeight / source.getHeight();
            int inkLeft = (int) Math.round(source.getInkX() * scaleX);
            int inkTop = (int) Math.round(source.getInkY() * scaleY);
            int inkWidth = Math.max(MIN_DRAW_SIZE,
                (int) Math.round(source.getInkWidth() * scaleX));
            int inkHeight = Math.max(MIN_DRAW_SIZE,
                (int) Math.round(source.getInkHeight() * scaleY));

            advancePosition(canvasWidth - inkWidth, canvasHeight - inkHeight, dt);

            // Animated sources loop on the monotonic clock; static ones always
            // return their single frame. While paused the clock freezes too.
            BufferedImage frame = source.frameAt(clockMs);
            int alpha = Math.max(0, Math.min(FULL_OPACITY, opacity.getAsInt()));
            BufferedImage image;
            if (!colourShift.getAsBoolean())
            {
                image = scaledFor(source, frame, drawWidth, drawHeight, alpha);
            }
            else if ((long) frame.getWidth() * frame.getHeight() < (long) drawWidth * drawHeight)
            {
                // The hue rotation walks every pixel it is given, so it runs on
                // whichever of the two is smaller. An item sprite is 36x32 at
                // any draw size, so it is cheaper to rotate before scaling.
                image = scaledFor(source, tintedFor(frame, nowMs, true), drawWidth, drawHeight, alpha);
            }
            else
            {
                // A source bigger than the draw size, which a custom image can
                // be, is cheaper to rotate once it has been scaled down.
                image = tintedFor(scaledFor(source, frame, drawWidth, drawHeight, alpha), nowMs,
                    false);
            }

            // x and y are where the visible part sits, so the image is drawn
            // back by its padding, which puts that padding off the edge.
            if (drawSubPixel)
            {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image,
                    AffineTransform.getTranslateInstance(x - inkLeft, y - inkTop), null);
            }
            else
            {
                graphics.drawImage(image, (int) Math.round(x) - inkLeft,
                    (int) Math.round(y) - inkTop, null);
            }
        }

        private void clearCaches()
        {
            scaledFrames.clear();
            tintedFrames.clear();
            scaledSource = null;
            tintedStep = -1;
        }

        /**
         * Move along the 45-degree path, folding the travelled distance exactly
         * into the reflected path. A gap between frames, from a world hop, a
         * client freeze or a laptop resume, puts the picture where it would be
         * had it kept moving the whole time, bounces included.
         */
        private void advancePosition(int travelWidth, int travelHeight, double dt)
        {
            travelWidth = Math.max(0, travelWidth);
            travelHeight = Math.max(0, travelHeight);

            if (!positionInitialized)
            {
                // Fixed fractions rather than a random spot: the start is well
                // clear of the edges and repeats across sessions, so the first
                // bounce is never immediate and the motion is reproducible.
                x = travelWidth * startFractionX;
                y = travelHeight * startFractionY;
                positionInitialized = true;
            }

            double step = speed.get().getPixelsPerSecond() * dt;

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
         * The given frame pre-scaled to the current draw size and faded to the
         * configured opacity, computed once per frame and cached until the
         * source image, draw size or opacity changes. Baking the fade in here
         * keeps the render itself a plain draw at any opacity. The tint cache
         * is keyed by these scaled frames, so it resets alongside, and the hue
         * rotation carries the alpha through untouched.
         */
        private BufferedImage scaledFor(AnimatedImage source, BufferedImage frame, int width,
            int height, int opacity)
        {
            if (scaledSource != source || scaledWidth != width || scaledHeight != height
                || scaledOpacity != opacity || scaledFrames.size() > 32)
            {
                scaledFrames.clear();
                tintedFrames.clear();
                scaledSource = source;
                scaledWidth = width;
                scaledHeight = height;
                scaledOpacity = opacity;
            }

            return scaledFrames.computeIfAbsent(frame, f ->
            {
                if (f.getWidth() == width && f.getHeight() == height && opacity >= FULL_OPACITY)
                {
                    return f;
                }
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (opacity < FULL_OPACITY)
                {
                    // Drawn onto an empty image, so this multiplies the
                    // source's own alpha rather than blending against anything.
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                        opacity / (float) FULL_OPACITY));
                }
                g.drawImage(f, 0, 0, width, height, null);
                g.dispose();
                return scaled;
            });
        }

        /**
         * The draw-size frame with the current hue rotation applied. The cache
         * is dropped when the hue moves on, but no more often than
         * {@link #TINT_MIN_INTERVAL_MS}, so a bounce on every frame cannot make
         * this walk the image pixels 60 times a second. A negative
         * {@link #tintedStep} means no tint is cached yet and the interval does
         * not apply, since the monotonic clock's epoch is arbitrary.
         */
        private BufferedImage tintedFor(BufferedImage source, long nowMs, boolean scaledFromTinted)
        {
            if (tintedStep != hueStep
                && (tintedStep < 0 || nowMs - tintedAtMs >= TINT_MIN_INTERVAL_MS))
            {
                tintedFrames.clear();
                if (scaledFromTinted)
                {
                    // Rotating before scaling means the scaled copies were made
                    // from the tinted frames just dropped, so they go with them.
                    scaledFrames.clear();
                }
                tintedStep = hueStep;
                tintedAtMs = nowMs;
            }

            float hueShift = (tintedStep * HUE_STEP) % 1f;
            return tintedFrames.computeIfAbsent(source, f -> hueRotate(f, hueShift));
        }
    }
}
