# DVD Bounce

A picture bounces around your RuneLite client like the classic DVD-player
screensaver, drifting across the screen at 45 degrees and ricocheting off the
edges. Will it ever hit the corner?

## Features

- **Bring your own image** — point *Custom image path* at any PNG/JPG on your
  computer (your clan logo, your cat, anything). A colourful gradient square is
  bundled as the default.
- **Colour shift** — the image's colours rotate a step on every bounce, just
  like the DVD logo changing colour.
- **Corner-hit flash** — optional celebratory white flash when the image lands
  exactly in a corner (off by default, see warning below).
- **Size and speed sliders** — from a subtle 24 px drifter to a 512 px
  screen-filler.

## Configuration

| Setting | Default | Notes |
|---|---|---|
| Custom image path | *(blank)* | Absolute path, e.g. `C:\Users\you\Pictures\logo.png`. Blank = bundled placeholder. Falls back to the placeholder if the file can't be read. |
| Image size (px) | 112 | Width; height follows the image's aspect ratio. |
| Speed (px/s) | 208 | |
| Colour shift on bounce | on | |
| Corner-hit flash | off | |

Animated GIFs are not supported — only the first frame is shown.

## Photosensitivity warning

The optional **corner-hit flash** briefly flashes the whole client white. It is
off by default; leave it off if you are sensitive to sudden flashes.

## License

BSD 2-Clause. All code and the bundled placeholder image are original to this
plugin.
