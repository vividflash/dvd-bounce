# DVD Bounce

An item or your own picture bounces around the client like the DVD screensaver. Will hit the
corner.

## Features

- **Bounce an in-game item**: put an item ID in *Item ID* to bounce that item's
  sprite. Defaults to the rubber chicken.
- **Bring your own image**: drop a PNG, JPG, GIF or BMP into your
  `.runelite/dvd-bounce` folder and put its file name in *Custom image file*.
  Animated GIFs play while they bounce. A file that cannot be read bounces a
  notice saying so.
- **Both at once**: the item and your image are separate pictures with separate
  settings, so either or both can be on. They start in different spots and head
  in different directions.
- **Colour shift**: the colours rotate a step on every bounce, like the DVD
  logo. A corner hits two edges, so it shifts two steps.
- **Size, opacity and speed per picture**: size 24 to 512 px, opacity 10 to
  100%, and eight speed presets from 15 to 600 px/s per axis.

## Configuration

| Setting | Default | Notes |
|---|---|---|
| FPS mode | Adaptive | Adaptive follows the measured frame rate; Crisp (60fps) forces whole-pixel rendering (sharpest); Smooth (Unlocked) forces sub-pixel rendering for unlocked/high fps. |

Each picture has its own section with the same settings.

| Item | Default | Notes |
|---|---|---|
| Bounce an item | on | |
| Item ID | 4566 (Rubber chicken) | The item whose sprite bounces. An ID with no item falls back to the rubber chicken. |
| Size (px) | 144 | Width; height follows the aspect ratio. Item sprites are 36x32, so larger sizes are scaled up and look blurry. |
| Opacity | 100% | How solid the picture is, from 10 to 100. |
| Speed | Classic | Ultra slow to Ultra fast (15-600 px/s per axis; travel is at 45 degrees, so about 1.4x that along the diagonal). Steps stay evenly paced at 60 fps, so slow speeds don't judder. |
| Colour shift on bounce | on | |

| Custom image | Default | Notes |
|---|---|---|
| Bounce a custom image | off | |
| Custom image file | *(blank)* | File name inside your `.runelite/dvd-bounce` folder (created when the plugin starts), e.g. `logo.png`. A name that cannot be read bounces a notice saying so, plus one chat line naming the file. |
| Reload image file | off | Re-reads the file and applies a name you just typed. Ticking or unticking both trigger it. |
| Size (px) | 144 | As above. |
| Opacity | 100% | As above. |
| Speed | Classic | As above. |
| Colour shift on bounce | on | |

Animated GIFs play, looping continuously. To keep memory bounded, frames are
downscaled to at most 512 px on their longest side and long animations are
truncated to the first 30 frames. A GIF that declares a canvas larger than
2048x2048 loads as a single frame, and any source above 16 million pixels
(4096x4096) shows the notice instead.

After replacing a file under the same name, tick *Reload image file* to read it
again.

## License

BSD 2-Clause.

---

Co-A: Fable 5
