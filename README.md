# DVD Bounce

An item or your own picture bounces around the client like the DVD
screensaver. Will hit the corner.

## Features

- **Bounce an in-game item**: put an item ID in *Item ID* and that item's
  sprite is what bounces. The rubber chicken is the default.
- **Bring your own image**: drop a PNG, JPG, GIF or BMP into your
  `.runelite/dvd-bounce` folder and put its file name in *Custom image file*.
  Animated GIFs play while they bounce, and a file that cannot be read bounces
  a notice saying so instead of quietly falling back.
- **Both at once**: the item and your image are separate pictures with separate
  settings, so either or both can be on. They start apart and travel opposite
  ways, so two pictures never move as one.
- **Colour shift**: a picture's colours rotate a step on every bounce, just
  like the DVD logo changing colour. A corner hits two edges, so it shifts
  two steps.
- **Size, opacity and speed per picture**: from a subtle 24 px drifter to a
  512 px screen-filler, at any opacity from faint to solid; speeds from Ultra
  slow to Ultra fast, tuned to stay judder-free at 60 fps.

## Configuration

Each picture has its own section with the same settings, so the item and
the custom image can look and move completely differently.

| Item | Default | Notes |
|---|---|---|
| Bounce an item | on | |
| Item ID | 4566 (Rubber chicken) | The item whose sprite bounces. An ID the client has no item for falls back to the rubber chicken. |
| Size (px) | 144 | Width; height follows the aspect ratio. Item sprites are 36x32, so bigger sizes scale them up and soften them. |
| Opacity | 100% | How solid the picture is, from 10 to 100. |
| Speed | Classic | Ultra slow to Ultra fast (15-600 px/s per axis; travel is at 45 degrees, so about 1.4x that along the diagonal). Fixed presets that keep pixel steps evenly paced at 60 fps, so slow speeds don't judder. |
| Colour shift on bounce | on | |

| Custom image | Default | Notes |
|---|---|---|
| Bounce a custom image | off | |
| Custom image file | *(blank)* | File name inside your `.runelite/dvd-bounce` folder (created when the plugin starts), e.g. `logo.png`. A name that cannot be read bounces a notice saying so, plus one chat line naming the file. |
| Reload image file | off | Re-reads the file, and applies a name you just typed. The tick carries no meaning. |
| Size (px) | 144 | As above. |
| Opacity | 100% | As above. |
| Speed | Classic | As above. |
| Colour shift on bounce | on | |

| Shared | Default | Notes |
|---|---|---|
| FPS mode | Adaptive | Adaptive follows the measured frame rate; Crisp (60fps) forces whole-pixel rendering (sharpest); Smooth (Unlocked) forces sub-pixel rendering for unlocked/high fps. Applies to both pictures, since it follows the client's frame rate rather than the picture. |

Animated GIFs play, looping continuously. To keep memory bounded, frames are
downscaled to at most 512 px on their longest side and long animations are
truncated to the first 30 frames. A GIF that declares a canvas larger than
2048x2048 loads as a single frame, and any source above 16 million pixels is
refused, which shows the notice. That is 4096x4096, in whatever shape those
pixels come.

After replacing a file under the same name, tick *Reload image file* to read it
again. That control is also where to click after typing a file name, because
the client only takes what you typed once the box loses focus.

## License

BSD 2-Clause. All code is original to this plugin, and no images are bundled:
item sprites are drawn by the client through RuneLite's item manager.

---

Co-A: Fable 5
