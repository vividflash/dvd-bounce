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

import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("dvdbounce")
public interface DvdBounceConfig extends Config
{
    @ConfigSection(
        name = "Item",
        description = "",
        position = 1
    )
    String itemSection = "itemSection";

    @ConfigSection(
        name = "Custom image",
        description = "",
        position = 2
    )
    String customSection = "customSection";

    @ConfigItem(
        keyName = "fpsMode",
        name = "FPS mode",
        description = "Crisp snaps to whole pixels, best at 60 fps. Smooth draws at sub-pixel positions, best at high fps. Adaptive picks by measured frame rate.",
        position = 0
    )
    default FpsMode fpsMode()
    {
        return FpsMode.ADAPTIVE;
    }

    @ConfigItem(
        keyName = "itemEnabled",
        name = "Bounce an item",
        description = "",
        position = 1,
        section = itemSection
    )
    default boolean itemEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "itemId",
        name = "Item ID",
        description = "An unknown ID falls back to the rubber chicken.",
        position = 2,
        section = itemSection
    )
    default int itemId()
    {
        return ItemID.RUBBER_CHICKEN;
    }

    @ConfigItem(
        keyName = "itemSize",
        name = "Size (px)",
        description = "Width. Item sprites are 36x32, so bigger sizes soften them.",
        position = 3,
        section = itemSection
    )
    @Range(min = 24, max = 512)
    default int itemSize()
    {
        return 144;
    }

    @ConfigItem(
        keyName = "itemOpacity",
        name = "Opacity",
        description = "",
        position = 4,
        section = itemSection
    )
    @Range(min = 10, max = 100)
    @Units(Units.PERCENT)
    default int itemOpacity()
    {
        return 100;
    }

    @ConfigItem(
        keyName = "itemSpeed",
        name = "Speed",
        description = "Per axis, from Ultra slow (15 px/s) to Ultra fast (600 px/s).",
        position = 5,
        section = itemSection
    )
    default BounceSpeed itemSpeed()
    {
        return BounceSpeed.CLASSIC;
    }

    @ConfigItem(
        keyName = "itemColourShift",
        name = "Colour shift on bounce",
        description = "",
        position = 6,
        section = itemSection
    )
    default boolean itemColourShift()
    {
        return true;
    }

    @ConfigItem(
        keyName = "customEnabled",
        name = "Bounce a custom image",
        description = "",
        position = 1,
        section = customSection
    )
    default boolean customEnabled()
    {
        return false;
    }

    @ConfigItem(
        keyName = "customImagePath",
        name = "Custom image file",
        description = "File name of an image in .runelite/plugin-data/dvd-bounce. PNG, JPG, GIF or BMP; animated GIFs play.",
        position = 2,
        section = customSection
    )
    default String customImageFile()
    {
        return "";
    }

    @ConfigItem(
        keyName = "reloadImage",
        name = "Reload image file",
        description = "Re-reads the file. Ticking and unticking both reload.",
        position = 3,
        section = customSection
    )
    default boolean reloadImage()
    {
        return false;
    }

    @ConfigItem(
        keyName = "customSize",
        name = "Size (px)",
        description = "Width.",
        position = 4,
        section = customSection
    )
    @Range(min = 24, max = 512)
    default int customSize()
    {
        return 144;
    }

    @ConfigItem(
        keyName = "customOpacity",
        name = "Opacity",
        description = "",
        position = 5,
        section = customSection
    )
    @Range(min = 10, max = 100)
    @Units(Units.PERCENT)
    default int customOpacity()
    {
        return 100;
    }

    @ConfigItem(
        keyName = "customSpeed",
        name = "Speed",
        description = "Per axis, from Ultra slow (15 px/s) to Ultra fast (600 px/s).",
        position = 6,
        section = customSection
    )
    default BounceSpeed customSpeed()
    {
        return BounceSpeed.CLASSIC;
    }

    @ConfigItem(
        keyName = "customColourShift",
        name = "Colour shift on bounce",
        description = "",
        position = 7,
        section = customSection
    )
    default boolean customColourShift()
    {
        return true;
    }
}
