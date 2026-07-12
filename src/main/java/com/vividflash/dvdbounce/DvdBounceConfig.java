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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("dvdbounce")
public interface DvdBounceConfig extends Config
{
    @ConfigItem(
        keyName = "customImagePath",
        name = "Custom image file",
        description = "File name of an image inside your .runelite/dvd-bounce folder (created when the plugin starts) to bounce instead of the bundled placeholder. PNG, JPG, GIF, BMP; animated GIFs play. Leave blank for the placeholder.",
        position = 0
    )
    default String customImageFile()
    {
        return "";
    }

    @ConfigItem(
        keyName = "imageSize",
        name = "Image size (px)",
        description = "Width of the bouncing image in pixels (height follows the image's aspect ratio)",
        position = 1
    )
    @Range(min = 24, max = 512)
    default int imageSize()
    {
        return 112;
    }

    @ConfigItem(
        keyName = "bounceSpeed",
        name = "Speed",
        description = "How fast the image travels, from Ultra slow (15 px/s) to Ultra fast (600 px/s). Fixed steps chosen to stay judder-free at 60 fps; higher or uncapped frame rates are smooth with any of them.",
        position = 2
    )
    default BounceSpeed bounceSpeed()
    {
        return BounceSpeed.CLASSIC;
    }

    @ConfigItem(
        keyName = "colourShift",
        name = "Colour shift on bounce",
        description = "Rotate the image's colours a step every time it bounces off an edge, like the DVD logo",
        position = 3
    )
    default boolean colourShift()
    {
        return true;
    }
}
