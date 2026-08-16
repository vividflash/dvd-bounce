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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * A two-line sign drawn at runtime, used as the bouncing picture when the
 * chosen custom image cannot be used, so what is wrong is on screen rather
 * than only in a log. Drawing it here rather than shipping an image keeps the
 * plugin free of bundled assets.
 */
final class NoticeImage
{
    /**
     * Text size the sign is drawn at. The overlay scales the whole sign to the
     * configured picture width, so this is the resolution the letters are
     * rasterised at, not the size they appear on screen.
     */
    private static final int FONT_SIZE = 20;

    /**
     * Space between the text and the border, which is all the sign holds, and
     * the border itself. Derived from the text size so the sign keeps its
     * proportions if that changes.
     */
    private static final int PAD_X = FONT_SIZE * 2 / 3;
    private static final int PAD_Y = FONT_SIZE / 2;
    private static final float BORDER_WIDTH = FONT_SIZE / 6f;

    private static final Color PANEL = new Color(0x2A, 0x06, 0x06, 0xE6);
    private static final Color BORDER = new Color(0xFF, 0x50, 0x3C);
    private static final Color TEXT = new Color(0xFF, 0xE4, 0xDE);

    private NoticeImage()
    {
    }

    static AnimatedImage of(String firstLine, String secondLine)
    {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE);

        // Measured on a throwaway context, because the canvas cannot be sized
        // until the text it has to hold has been measured. Measuring the glyph
        // outlines rather than the font metrics keeps the box tight to the
        // pixels the letters actually cover.
        BufferedImage probeImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D probe = probeImage.createGraphics();
        FontRenderContext context = probe.getFontRenderContext();
        Shape first = font.createGlyphVector(context, firstLine).getOutline();
        Shape second = font.createGlyphVector(context, secondLine).getOutline();
        double lineGap = probe.getFontMetrics(font).getHeight();
        probe.dispose();

        Rectangle2D firstInk = first.getBounds2D();
        Rectangle2D secondInk = second.getBounds2D();
        double top = Math.min(firstInk.getMinY(), secondInk.getMinY() + lineGap);
        double bottom = Math.max(firstInk.getMaxY(), secondInk.getMaxY() + lineGap);

        int width = (int) Math.ceil(Math.max(firstInk.getWidth(), secondInk.getWidth()))
            + 2 * PAD_X;
        int height = (int) Math.ceil(bottom - top) + 2 * PAD_Y;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int corner = Math.min(width, height) / 4;
        g.setColor(PANEL);
        g.fillRoundRect(0, 0, width, height, corner, corner);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(BORDER_WIDTH));
        int inset = Math.round(BORDER_WIDTH / 2);
        g.drawRoundRect(inset, inset, width - 1 - 2 * inset, height - 1 - 2 * inset,
            corner - inset, corner - inset);

        g.setColor(TEXT);
        double offsetY = height / 2.0 - (top + bottom) / 2;
        g.fill(AffineTransform.getTranslateInstance(
            width / 2.0 - firstInk.getCenterX(), offsetY).createTransformedShape(first));
        g.fill(AffineTransform.getTranslateInstance(
            width / 2.0 - secondInk.getCenterX(), offsetY + lineGap)
            .createTransformedShape(second));

        g.dispose();
        return AnimatedImage.of(image);
    }
}
