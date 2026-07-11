# DVD Bounce

A picture bounces around your RuneLite client like the classic DVD-player
screensaver, drifting across the screen at 45 degrees and ricocheting off the
edges. Will it ever hit the corner?

## Features

- **Bring your own image** — drop a PNG, JPG, GIF or BMP into your
  `.runelite/dvd-bounce` folder (your clan logo, your cat, anything) and put
  its file name in *Custom image file*. Animated GIFs play while they bounce.
  A colourful gradient square is bundled as the default.
- **Colour shift** — the image's colours rotate a step on every bounce, just
  like the DVD logo changing colour.
- **Corner-hit flash** — optional celebratory white flash when the image lands
  exactly in a corner (off by default, see warning below).
- **Size and speed sliders** — from a subtle 24 px drifter to a 512 px
  screen-filler.

## Configuration

| Setting | Default | Notes |
|---|---|---|
| Custom image file | *(blank)* | File name inside your `.runelite/dvd-bounce` folder (created when the plugin starts), e.g. `logo.png`. Blank = bundled placeholder. Falls back to the placeholder if the file can't be read. |
| Image size (px) | 112 | Width; height follows the image's aspect ratio. |
| Speed (px/s) | 208 | |
| Colour shift on bounce | on | |
| Corner-hit flash | off | |

Animated GIFs play, looping continuously. To keep memory bounded, frames are
downscaled to at most 512 px on their longest side and very long animations
are truncated (at most 150 frames / 64 MB decoded).

## Photosensitivity warning

The optional **corner-hit flash** briefly flashes the whole client white. It is
off by default; leave it off if you are sensitive to sudden flashes.

## License

BSD 2-Clause. All code and the bundled placeholder image are original to this
plugin.
