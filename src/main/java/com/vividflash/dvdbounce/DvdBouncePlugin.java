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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
    name = "DVD Bounce",
    description = "An item or your own picture bounces around the client like the DVD screensaver. Will hit the corner.",
    tags = {"dvd", "bounce", "screensaver", "overlay", "item", "fun"}
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
    private static final String ITEM_ID_KEY = "itemId";
    private static final String ITEM_ENABLED_KEY = "itemEnabled";
    private static final String CUSTOM_ENABLED_KEY = "customEnabled";

    /**
     * Ticking this re-reads the file. It also gives the user somewhere to click
     * after typing a file name, since the client only takes the text of a
     * setting once the box loses focus.
     */
    private static final String RELOAD_IMAGE_KEY = "reloadImage";
    private static final String LAST_SEEN_VERSION_KEY = "lastSeenVersion";

    /** Item drawn when the configured id is not one the client has an item for. */
    private static final int DEFAULT_ITEM_ID = ItemID.RUBBER_CHICKEN;

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
    private static final String MIGRATION_VERSION = "1.6";

    /**
     * The boolean marker 1.4 used before {@link #MIGRATION_KEY}. A profile
     * carrying it has had the 1.4 sweep and no later one, so it counts as
     * swept at {@link #LEGACY_MIGRATION_VERSION} rather than at whatever
     * version the sweep has since reached.
     */
    private static final String LEGACY_MIGRATION_KEY = "migratedV14";
    private static final String LEGACY_MIGRATION_VERSION = "1.4";

    /**
     * Config items removed by earlier versions; cleared from profiles by the
     * sweep. Add to this and bump {@link #MIGRATION_VERSION} together.
     */
    private static final String[] DEAD_KEYS = {"speed", "cornerFlash", LEGACY_MIGRATION_KEY,
        "imageSize", "bounceSpeed", "colourShift"};

    /** Keep in sync with build.gradle and runelite-plugin.properties on every release. */
    private static final String VERSION = "1.6";
    private static final String UPDATE_MESSAGE =
        "DVD Bounce v1.6: Added support for in-game items. Added support for opacity. Added support for showing both at the same time. Each picture has its own settings. Replaces the default image with a default item.";

    /** Dark red, for legibility against the opaque chatbox background. */
    private static final Color NOTICE_COLOR = new Color(0x480000);

    /** Longest custom image name repeated back in a chat notice. */
    private static final int NAME_NOTICE_LIMIT = 40;

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

    @Inject
    private ItemManager itemManager;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    /** One update check per session; reset on startUp. */
    private boolean updateChecked;

    /**
     * A custom image that failed to load and still owes the user a chat notice,
     * and the last name a notice was given for. Together they say it once per
     * broken name, and only when there is a chat box to say it in. A successful
     * load clears both, so a file that breaks again is reported again.
     */
    private volatile ImageFailure pendingFailure;
    private volatile String announcedFailureName;

    /**
     * The configured item's sprite, published once the client has drawn it.
     * Null until then, and while the configured item has no sprite at all, in
     * which case nothing is drawn.
     */
    private volatile AnimatedImage itemImage;

    /**
     * The configured custom image, preloaded on the executor at startup and
     * whenever its config key changes, so the overlay's render loop never
     * touches the disk. Null when unset or unloadable, in which case
     * {@link #customNotice} is drawn in its place.
     */
    private volatile AnimatedImage customImage;

    /**
     * The sign shown in place of a custom image that is not set or cannot be
     * read, so the picture on screen says what is wrong. Drawn only while the
     * custom picture is switched on.
     */
    private volatile AnimatedImage customNotice;

    /**
     * Load generations: each (re)load bumps its counter and only the newest
     * load may publish its result, so a slow decode or a late sprite cannot
     * overwrite a newer config edit, and results arriving after shutDown are
     * dropped.
     */
    private final AtomicInteger imageLoadGen = new AtomicInteger();
    private final AtomicInteger itemLoadGen = new AtomicInteger();

    /**
     * Guards the compare-and-publish in {@link #reloadCustomImage(boolean)} and
     * {@link #reloadItemImage()} against shutDown's invalidate-and-clear, so a
     * load that read a generation just before shutDown cannot assign
     * afterwards. Held only across field writes.
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
        reloadItemImage();
        reloadCustomImage(false);
        // startUp and shutDown run on the Swing thread, while the overlay's
        // state and frame caches belong to the client thread. Resetting and
        // registering there keeps the two off each other's collections, and
        // registering in the same task means no frame can render against
        // state left over from the previous run.
        clientThread.invoke(() ->
        {
            overlay.resetState();
            overlayManager.add(overlay);
        });
    }

    @Override
    protected void shutDown()
    {
        // Release all decoded frames so a disabled plugin pins no heap; the
        // generation bumps also invalidate any load still in flight, and any
        // failure it was about to announce.
        synchronized (imagePublishLock)
        {
            imageLoadGen.incrementAndGet();
            itemLoadGen.incrementAndGet();
            customImage = null;
            itemImage = null;
            customNotice = null;
            pendingFailure = null;
            announcedFailureName = null;
        }
        // Unregistered on the client thread, like the registration in startUp,
        // so the two keep their order however close together they land, and so
        // the frame caches are not cleared under a running render.
        clientThread.invoke(() ->
        {
            overlayManager.remove(overlay);
            overlay.clearImageCaches();
        });
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
                announceImageFailure();
                break;
            default:
                break;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!CONFIG_GROUP.equals(event.getGroup()))
        {
            return;
        }
        if (CUSTOM_IMAGE_KEY.equals(event.getKey()) || CUSTOM_ENABLED_KEY.equals(event.getKey())
            || RELOAD_IMAGE_KEY.equals(event.getKey()))
        {
            // Switching the custom picture on reads the file and builds its
            // sign if it is unusable; switching it off drops both again.
            reloadCustomImage(RELOAD_IMAGE_KEY.equals(event.getKey()));
        }
        else if (ITEM_ID_KEY.equals(event.getKey()))
        {
            reloadItemImage();
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
                .append(NOTICE_COLOR, message)
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
            swept = LEGACY_MIGRATION_VERSION;
        }
        if (MIGRATION_VERSION.equals(swept))
        {
            return;
        }
        configManager.setConfiguration(CONFIG_GROUP, MIGRATION_KEY, MIGRATION_VERSION);

        // 1.6 gave each picture its own settings. A profile from before that
        // had one set of them, applied to whatever it was bouncing, so both
        // pictures inherit those values and whichever gets switched on looks
        // the way it used to. Read before the dead keys below are cleared.
        carryForward("imageSize", "itemSize", "customSize");
        carryForward("bounceSpeed", "itemSpeed", "customSpeed");
        carryForward("colourShift", "itemColourShift", "customColourShift");

        // A file name used to be the whole choice of picture, so a profile
        // holding one keeps bouncing that file and not the item.
        String configuredFile = configManager.getConfiguration(CONFIG_GROUP, CUSTOM_IMAGE_KEY);
        if (configuredFile != null && !configuredFile.trim().isEmpty())
        {
            configManager.setConfiguration(CONFIG_GROUP, CUSTOM_ENABLED_KEY, true);
            configManager.setConfiguration(CONFIG_GROUP, ITEM_ENABLED_KEY, false);
        }

        for (String dead : DEAD_KEYS)
        {
            configManager.unsetConfiguration(CONFIG_GROUP, dead);
        }
    }

    /** Copy a retired key's value onto the keys that replaced it, if it is set. */
    private void carryForward(String oldKey, String... newKeys)
    {
        String value = configManager.getConfiguration(CONFIG_GROUP, oldKey);
        if (value == null || value.isEmpty())
        {
            return;
        }
        for (String newKey : newKeys)
        {
            configManager.setConfiguration(CONFIG_GROUP, newKey, value);
        }
    }

    /**
     * The item picture. Called from the overlay every frame, and never does any
     * I/O; the sprite arrives via {@link #reloadItemImage()}. Null until it
     * does, which the overlay draws as nothing.
     */
    AnimatedImage resolveItemImage()
    {
        return itemImage;
    }

    /**
     * The custom picture: the loaded file, or the notice sign while the file is
     * unset or unreadable. Null only until the first load finishes.
     */
    AnimatedImage resolveCustomImage()
    {
        AnimatedImage custom = customImage;
        return custom != null ? custom : customNotice;
    }

    /**
     * (Re)request the configured item's sprite from {@link ItemManager}, which
     * hands back an image the client fills in on a later tick, once the item
     * cache is up. Publishing happens in its loaded callback so the overlay
     * never scales blank pixels, and each load publishes a new wrapper, which
     * is what makes the overlay rebuild its scale and tint caches.
     *
     * <p>An id the client has no sprite for never loads, and nothing is drawn
     * until the id is changed to one that does.
     */
    private void reloadItemImage()
    {
        int gen = itemLoadGen.incrementAndGet();
        // Dropped before the request rather than swapped after it, unlike the
        // custom image, because the old sprite is the wrong item the moment
        // the id changes, and a blank tick or two costs nothing.
        synchronized (imagePublishLock)
        {
            itemImage = null;
        }

        int configured = config.itemId();
        clientThread.invoke(() ->
        {
            if (gen != itemLoadGen.get())
            {
                // A newer reload, or shutDown, has taken over.
                return true;
            }
            if (client.getItemCount() <= 0)
            {
                // Item cache is not up yet. Returning false leaves this queued
                // for the next tick.
                return false;
            }

            AsyncBufferedImage sprite = itemManager.getImage(usableItemId(configured));
            if (sprite == null)
            {
                log.debug("No item sprite available for id {}", configured);
                return true;
            }
            // Registered outside the publish lock. The callback takes that
            // lock, and the client runs it holding the image's own monitor, so
            // registering from inside would take the two in opposite orders.
            sprite.onLoaded(() ->
            {
                synchronized (imagePublishLock)
                {
                    if (gen == itemLoadGen.get())
                    {
                        itemImage = AnimatedImage.of(sprite);
                    }
                }
            });
            return true;
        });
    }

    /**
     * The configured id when the client has an item under it, and the default
     * item otherwise. An id outside the cache, or one the cache has no item
     * for, draws a placeholder sprite rather than nothing, so it is worth
     * resolving before the image is requested. Runs on the client thread.
     */
    private int usableItemId(int configured)
    {
        if (configured < 0 || configured >= client.getItemCount())
        {
            return DEFAULT_ITEM_ID;
        }
        ItemComposition composition = itemManager.getItemComposition(configured);
        String name = composition == null ? null : composition.getName();
        if (name == null || name.isEmpty() || "null".equalsIgnoreCase(name))
        {
            log.debug("Item {} is unused in the cache, drawing the default item", configured);
            return DEFAULT_ITEM_ID;
        }
        return configured;
    }

    /**
     * (Re)load the configured custom image on the executor, publishing into
     * {@link #customImage}. Runs off the client thread so neither rendering
     * nor config edits ever wait on disk or GIF decoding.
     */
    private void reloadCustomImage(boolean asked)
    {
        int gen = imageLoadGen.incrementAndGet();
        String configured = config.customImageFile();
        String name = configured == null ? "" : configured.trim();
        // Nothing is read, drawn or held while the custom picture is switched
        // off. A default profile bounces the item with no file name set, and
        // would otherwise pay a decode and keep a sign it never shows.
        boolean wanted = config.customEnabled();
        executor.execute(() ->
        {
            AnimatedImage loaded = null;
            boolean tooLarge = false;
            if (!name.isEmpty() && wanted)
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
                        log.warn("Could not load custom image {} from {}, showing a notice instead", name, PLUGIN_DIR);
                    }
                }
                catch (AnimatedImage.TooLargeException e)
                {
                    // Told apart from the rest so the user is not sent looking
                    // for a typo in a name that is perfectly correct.
                    tooLarge = true;
                    log.warn("Custom image {} is too large to load: {}", name, e.getMessage());
                }
                catch (IOException | RuntimeException e)
                {
                    // ImageIO throws unchecked on malformed pixel data as well
                    // as IOException on unreadable files. Either way the load
                    // yields null and the notice takes its place.
                    loaded = null;
                    log.warn("Failed to read custom image {}, showing a notice instead", name, e);
                }
            }
            // Loaded outside the lock, which covers only the publish.
            AnimatedImage notice = null;
            if (loaded == null && wanted)
            {
                if (name.isEmpty())
                {
                    notice = AnimatedImage.of(ImageUtil.loadImageResource(getClass(), "notice-no-file-name.png"));
                }
                else if (tooLarge)
                {
                    notice = AnimatedImage.of(ImageUtil.loadImageResource(getClass(), "notice-too-large.png"));
                }
                else
                {
                    notice = AnimatedImage.of(ImageUtil.loadImageResource(getClass(), "notice-wrong-file.png"));
                }
            }

            // Only worth saying while the custom picture is switched on. With
            // it off the file is not being drawn, so a broken name is not a
            // problem the user has yet.
            ImageFailure failure = loaded == null && !name.isEmpty() && wanted
                ? new ImageFailure(name, tooLarge, gen) : null;
            boolean announce = false;
            synchronized (imagePublishLock)
            {
                if (gen != imageLoadGen.get())
                {
                    return;
                }
                customImage = loaded;
                customNotice = notice;
                if (loaded != null || name.isEmpty())
                {
                    // The file reads now, or there is no file to read. Forget
                    // any failure, so one that comes back is reported again.
                    pendingFailure = null;
                    announcedFailureName = null;
                }
                else if (failure == null)
                {
                    // Still broken, but the picture is switched off, so there
                    // is nothing to say and anything waiting to be said is no
                    // longer worth saying. What has already been said stays
                    // remembered, or every off and on would repeat itself.
                    pendingFailure = null;
                }
                else if (asked || !name.equals(announcedFailureName))
                {
                    // A reload the user asked for answers even when the same
                    // name was already reported, or the control would look
                    // like it did nothing.
                    pendingFailure = failure;
                    announce = true;
                }
            }
            if (announce)
            {
                announceImageFailure();
            }
        });
    }

    /**
     * Tell the user their custom image could not be read, once the client is
     * logged in and there is a chat box for it. A failure found before that
     * waits for the login. Without this the notice on screen is the only sign
     * of which file the plugin could not open.
     */
    private void announceImageFailure()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        // Taking the failure and marking it announced under the lock, so a
        // login and a finishing load cannot both claim the same one.
        ImageFailure failure;
        synchronized (imagePublishLock)
        {
            failure = pendingFailure;
            if (failure == null || failure.generation != imageLoadGen.get())
            {
                pendingFailure = null;
                return;
            }
            pendingFailure = null;
            announcedFailureName = failure.name;
        }

        // The name comes from config and lands in a chat line, where angle
        // brackets would read as client formatting tags.
        String shown = failure.name.replace('<', ' ').replace('>', ' ');
        if (shown.length() > NAME_NOTICE_LIMIT)
        {
            shown = shown.substring(0, NAME_NOTICE_LIMIT) + "...";
        }
        if (failure.tooLarge)
        {
            announce("DVD Bounce could not use \"" + shown + "\": images are limited to"
                + " 16 million pixels (4096x4096).");
            return;
        }
        announce("DVD Bounce could not read \"" + shown + "\" in your .runelite/"
            + PLUGIN_DIR.getName() + " folder."
            + " Check the file name and that it ends in .png, .jpg, .gif or .bmp.");
    }

    /**
     * A custom image that could not be used, enough of why to say so, and the
     * load generation it came from. A failure is only worth announcing while
     * that generation is still the current one: a newer load has its own
     * answer, and shutDown bumps the counter precisely so a stopped plugin
     * says nothing.
     */
    private static final class ImageFailure
    {
        private final String name;
        private final boolean tooLarge;
        private final int generation;

        private ImageFailure(String name, boolean tooLarge, int generation)
        {
            this.name = name;
            this.tooLarge = tooLarge;
            this.generation = generation;
        }
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
}
