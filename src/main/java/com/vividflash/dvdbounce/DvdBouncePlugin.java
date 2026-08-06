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
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
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
     * The only folder this plugin reads from. Created on startup so users can
     * drop their image into it.
     */
    private static final File PLUGIN_DIR = new File(RuneLite.RUNELITE_DIR, "dvd-bounce");

    private static final String CONFIG_GROUP = "dvdbounce";
    private static final String CUSTOM_IMAGE_KEY = "customImagePath";
    private static final String LAST_SEEN_VERSION_KEY = "lastSeenVersion";

    /**
     * Marks a profile as having been shown one update notice. The key name is
     * the one 1.4 introduced, kept so profiles that already saw that notice are
     * not told again. A profile without it is shown {@link #UPDATE_MESSAGE}
     * once, which covers installs predating the mechanism and also means a
     * fresh install sees the current version's notice on its first login.
     */
    private static final String FIRST_NOTICE_KEY = "gifNoticeShown";

    /**
     * Records which release last swept {@link #DEAD_KEYS}. Version-stamped
     * rather than a flag, so a later release can add keys and sweep again.
     */
    private static final String MIGRATION_KEY = "migratedVersion";
    private static final String MIGRATION_VERSION = "1.4";

    /**
     * The boolean marker 1.4 used before {@link #MIGRATION_KEY}. A profile
     * carrying it has had the 1.4 sweep, so it counts as
     * {@link #MIGRATION_VERSION}.
     */
    private static final String LEGACY_MIGRATION_KEY = "migratedV14";

    /**
     * Config items removed by earlier versions; cleared from profiles by the
     * sweep. Add to this and bump {@link #MIGRATION_VERSION} together.
     */
    private static final String[] DEAD_KEYS = {"speed", "cornerFlash", LEGACY_MIGRATION_KEY};

    /** Keep in sync with build.gradle and runelite-plugin.properties on every release. */
    private static final String VERSION = "1.5";
    private static final String UPDATE_MESSAGE =
        "DVD Bounce v1.5: broken or oversized custom images now fall back to the bundled logo, and the overlay no longer gets in the way of overlay management.";

    /** Dark red, for legibility against the opaque chatbox background. */
    private static final Color UPDATE_MESSAGE_COLOR = new Color(0x480000);

    @Inject
    private DvdBounceConfig config;

    @Inject
    private DvdBounceOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ChatMessageManager chatMessageManager;

    /** One update check per session; reset on startUp. */
    private boolean updateChecked;

    private AnimatedImage bundledPlaceholder;

    /**
     * The configured custom image, preloaded on the executor at startup and
     * whenever its config key changes, so the overlay's render loop never
     * touches the disk. Null when unset or unloadable, in which case the
     * overlay falls back to the bundled placeholder.
     */
    private volatile AnimatedImage customImage;

    /**
     * Load generation: each (re)load bumps the counter and only the newest
     * load may publish its result, so a slow decode cannot overwrite a newer
     * config edit, and results arriving after shutDown are dropped.
     */
    private final AtomicInteger imageLoadGen = new AtomicInteger();

    /**
     * Guards the compare-and-publish in {@link #reloadCustomImage()} against
     * shutDown's invalidate-and-clear, so a load that read the generation just
     * before shutDown cannot assign afterwards. Held only across field writes.
     */
    private final Object imagePublishLock = new Object();

    @Override
    protected void startUp()
    {
        updateChecked = false;
        if (!PLUGIN_DIR.exists() && !PLUGIN_DIR.mkdirs())
        {
            log.warn("Could not create plugin folder {}", PLUGIN_DIR);
        }
        migrateOnce();
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
        synchronized (imagePublishLock)
        {
            imageLoadGen.incrementAndGet();
            customImage = null;
        }
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
                maybeAnnounceUpdate();
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
     * One-time post-update notice on first login. A profile that has not been
     * shown a notice before gets one unconditionally; afterward it is a version
     * comparison. See {@link #FIRST_NOTICE_KEY} for what that first case covers.
     */
    private void maybeAnnounceUpdate()
    {
        if (updateChecked)
        {
            return;
        }
        updateChecked = true;

        String lastSeen = configManager.getConfiguration(CONFIG_GROUP, LAST_SEEN_VERSION_KEY);
        configManager.setConfiguration(CONFIG_GROUP, LAST_SEEN_VERSION_KEY, VERSION);

        if (!Boolean.parseBoolean(configManager.getConfiguration(CONFIG_GROUP, FIRST_NOTICE_KEY)))
        {
            configManager.setConfiguration(CONFIG_GROUP, FIRST_NOTICE_KEY, true);
            announce(UPDATE_MESSAGE);
        }
        else if (lastSeen != null && !lastSeen.isEmpty() && !VERSION.equals(lastSeen))
        {
            announce(UPDATE_MESSAGE);
        }
    }

    private void announce(String message)
    {
        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(new ChatMessageBuilder()
                .append(UPDATE_MESSAGE_COLOR, message)
                .build())
            .build());
    }

    /**
     * Clear config keys retired by earlier versions so they stop lingering in
     * profiles. Runs once per {@link #MIGRATION_VERSION}; the framework never
     * re-creates a non-item key.
     */
    private void migrateOnce()
    {
        String swept = configManager.getConfiguration(CONFIG_GROUP, MIGRATION_KEY);
        if (swept == null
            && Boolean.parseBoolean(configManager.getConfiguration(CONFIG_GROUP, LEGACY_MIGRATION_KEY)))
        {
            swept = MIGRATION_VERSION;
        }
        if (MIGRATION_VERSION.equals(swept))
        {
            return;
        }
        configManager.setConfiguration(CONFIG_GROUP, MIGRATION_KEY, MIGRATION_VERSION);
        for (String dead : DEAD_KEYS)
        {
            configManager.unsetConfiguration(CONFIG_GROUP, dead);
        }
    }

    /**
     * Resolve the image to bounce: the preloaded custom image if configured
     * and loadable, otherwise the bundled placeholder. Called from the
     * overlay every frame, and never does any I/O; loading happens on the
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
                        loaded = AnimatedImage.load(imageFile, MAX_SOURCE_DIMENSION);
                    }
                    if (loaded == null)
                    {
                        log.warn("Could not load custom image from {}, falling back to placeholder: {}", PLUGIN_DIR, name);
                    }
                }
                catch (IOException | RuntimeException e)
                {
                    // ImageIO throws unchecked on malformed pixel data as well
                    // as IOException on unreadable files. Either way the load
                    // yields null and the placeholder takes over.
                    loaded = null;
                    log.warn("Failed to read custom image, falling back to placeholder: {}", name, e);
                }
            }
            synchronized (imagePublishLock)
            {
                if (gen == imageLoadGen.get())
                {
                    customImage = loaded;
                }
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
