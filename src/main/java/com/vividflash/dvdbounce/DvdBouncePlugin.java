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
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
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

    @Inject
    private DvdBounceConfig config;

    @Inject
    private DvdBounceOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    private AnimatedImage bundledPlaceholder;

    /**
     * Cache of the last custom image loaded, keyed by its path, so the file is
     * read from disk only when the configured path changes.
     */
    private String cachedCustomPath;
    private AnimatedImage cachedCustomImage;

    @Override
    protected void startUp()
    {
        if (!PLUGIN_DIR.exists() && !PLUGIN_DIR.mkdirs())
        {
            log.warn("Could not create plugin folder {}", PLUGIN_DIR);
        }
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
     * disk I/O only happens when the configured file name changes.
     */
    AnimatedImage resolveSourceImage()
    {
        String customName = config.customImageFile();
        if (customName == null || customName.trim().isEmpty())
        {
            return bundledPlaceholder;
        }

        String name = customName.trim();
        if (name.equals(cachedCustomPath))
        {
            return cachedCustomImage != null ? cachedCustomImage : bundledPlaceholder;
        }

        // Remember the attempted name even on failure so a broken file is not
        // re-read from disk every frame.
        cachedCustomPath = name;
        cachedCustomImage = null;
        try
        {
            File imageFile = resolvePluginFile(name);
            if (imageFile != null && imageFile.isFile())
            {
                AnimatedImage loaded = AnimatedImage.load(imageFile,
                    MAX_SOURCE_DIMENSION, MAX_SOURCE_DIMENSION);
                if (loaded != null)
                {
                    cachedCustomImage = loaded;
                    return cachedCustomImage;
                }
            }
            log.warn("Could not load custom image from {}, falling back to placeholder: {}", PLUGIN_DIR, name);
        }
        catch (IOException e)
        {
            log.warn("Failed to read custom image, falling back to placeholder: {}", name, e);
        }
        return bundledPlaceholder;
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
