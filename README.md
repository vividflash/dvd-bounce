# DVD Bounce

A picture bounces around your client like the DVD screensaver. Will hit the
corner.

## Features

- **Bring your own image** — drop a PNG, JPG, GIF or BMP into your
  `.runelite/dvd-bounce` folder (your clan logo, your cat, anything) and put
  its file name in *Custom image file*. Animated GIFs play while they bounce.
  A colourful gradient square is bundled as the default.
- **Colour shift** — the image's colours rotate a step on every bounce, just
  like the DVD logo changing colour.
- **Size and speed sliders** — from a subtle 24 px drifter to a 512 px
  screen-filler.

## Configuration

| Setting | Default | Notes |
|---|---|---|
| Custom image file | *(blank)* | File name inside your `.runelite/dvd-bounce` folder (created when the plugin starts), e.g. `logo.png`. Blank = bundled placeholder. Falls back to the placeholder if the file can't be read. |
| Image size (px) | 112 | Width; height follows the image's aspect ratio. |
| Speed (px/s) | 208 | |
| Colour shift on bounce | on | |

Animated GIFs play, looping continuously. To keep memory bounded, frames are
downscaled to at most 512 px on their longest side and long animations are
truncated to the first 10 frames.

## License

BSD 2-Clause. All code and the bundled placeholder image are original to this
plugin.
