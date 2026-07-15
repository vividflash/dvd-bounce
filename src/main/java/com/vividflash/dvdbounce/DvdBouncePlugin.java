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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
    name = "DVD Bounce",
    description = "A picture bounces around your client like the DVD screensaver. Will hit the corner.",
    tags = {"dvd", "bounce", "screensaver", "overlay", "fun"}
)
public class DvdBouncePlugin extends Plugin
{
    /**
     * Custom images are downscaled to at most this many pixels on their longest
     * side before use, so the per-bounce colour-shift pass stays cheap even when
     * the user points the plugin at a full-size photo.
     */
    private static final int MAX_SOURCE_DIMENSION = 512;

    /**
     * All file I/O is restricted to this plugin-specific subfolder under
     * .runelite (Plugin Hub requirement). Created on startup so users can
     * drop their image into it.
     */
    private static final File PLUGIN_DIR = new File(RuneLite.RUNELITE_DIR, "dvd-bounce");

    private static final String CONFIG_GROUP = "dvdbounce";
    private static final String CUSTOM_IMAGE_KEY = "customImagePath";

    @Inject
    private DvdBounceConfig config;

    @Inject
    private DvdBounceOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ScheduledExecutorService executor;

    private AnimatedImage bundledPlaceholder;

    /**
     * The configured custom image, preloaded on the executor at startup and
     * whenever its config key changes, so the overlay's render loop never
     * touches the disk. Null when unset or unloadable — the overlay falls
     * back to the bundled placeholder.
     */
    private volatile AnimatedImage customImage;

    /**
     * Load generation: each (re)load bumps the counter and only the newest
     * load may publish its result, so a slow decode can't overwrite a newer
     * config edit — and results arriving after shutDown are dropped.
     */
    private final AtomicInteger imageLoadGen = new AtomicInteger();

    @Override
    protected void startUp()
    {
        if (!PLUGIN_DIR.exists() && !PLUGIN_DIR.mkdirs())
        {
            log.warn("Could not create plugin folder {}", PLUGIN_DIR);
        }
        bundledPlaceholder = loadBundledImage("placeholder.png");
        reloadCustomImage();
        overlay.resetState();
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        // Release all decoded frames so a disabled plugin pins no heap; the
        // generation bump also invalidates any load still in flight.
        imageLoadGen.incrementAndGet();
        customImage = null;
        bundledPlaceholder = null;
        overlay.clearImageCaches();
    }

    @Provides
    DvdBounceConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DvdBounceConfig.class);
    }

    /**
     * Pause the bounce while the client is busy with a world hop (or login /
     * reconnect) and resume shortly after, so the overlay adds no work while
     * the game is already struggling. Ordinary region loads while running
     * around stay untouched.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case HOPPING:
            case LOGGING_IN:
            case LOGIN_SCREEN:
            case CONNECTION_LOST:
                overlay.pause();
                break;
            case LOGGED_IN:
                overlay.scheduleResume();
                break;
            default:
                break;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (CONFIG_GROUP.equals(event.getGroup()) && CUSTOM_IMAGE_KEY.equals(event.getKey()))
        {
            reloadCustomImage();
        }
    }

    /**
     * Resolve the image to bounce: the preloaded custom image if configured
     * and loadable, otherwise the bundled placeholder. Called from the
     * overlay every frame; never does any I/O — loading happens on the
     * executor via {@link #reloadCustomImage()}.
     */
    AnimatedImage resolveSourceImage()
    {
        AnimatedImage custom = customImage;
        return custom != null ? custom : bundledPlaceholder;
    }

    /**
     * (Re)load the configured custom image on the executor, publishing into
     * {@link #customImage}. Runs off the client thread so neither rendering
     * nor config edits ever wait on disk or GIF decoding.
     */
    private void reloadCustomImage()
    {
        int gen = imageLoadGen.incrementAndGet();
        String configured = config.customImageFile();
        String name = configured == null ? "" : configured.trim();
        executor.execute(() ->
        {
            AnimatedImage loaded = null;
            if (!name.isEmpty())
            {
                try
                {
                    File imageFile = resolvePluginFile(name);
                    if (imageFile != null && imageFile.isFile())
                    {
                        loaded = AnimatedImage.load(imageFile,
                            MAX_SOURCE_DIMENSION, MAX_SOURCE_DIMENSION);
                    }
                    if (loaded == null)
                    {
                        log.warn("Could not load custom image from {}, falling back to placeholder: {}", PLUGIN_DIR, name);
                    }
                }
                catch (IOException e)
                {
                    log.warn("Failed to read custom image, falling back to placeholder: {}", name, e);
                }
            }
            if (gen == imageLoadGen.get())
            {
                customImage = loaded;
            }
        });
    }

    /**
     * Resolve a configured file name inside the plugin's .runelite subfolder.
     * Only files within that folder are ever read; a name that escapes it
     * (e.g. via "..") resolves to null.
     */
    private static File resolvePluginFile(String name)
    {
        try
        {
            File file = new File(PLUGIN_DIR, name);
            String base = PLUGIN_DIR.getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(base) ? file : null;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private AnimatedImage loadBundledImage(String resource)
    {
        try (InputStream in = getClass().getResourceAsStream(resource))
        {
            if (in == null)
            {
                log.warn("Bundled {} resource not found on classpath", resource);
                return null;
            }
            return AnimatedImage.of(ImageIO.read(in));
        }
        catch (IOException e)
        {
            log.warn("Failed to load bundled {}", resource, e);
            return null;
        }
    }
}
