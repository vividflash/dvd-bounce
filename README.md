# DVD Bounce

An item or your own picture bounces around the client like the DVD
screensaver. Will hit the corner.

## Features

The item and your image are separate pictures with separate settings, so
either or both can be on.

**General**

- **FPS mode**: `Adaptive` follows the measured frame rate. `Crisp (60fps)`
  forces whole-pixel rendering (sharpest). `Smooth (Unlocked)` forces
  sub-pixel rendering for unlocked or high fps. Default `Adaptive`.

**Item**

- **Bounce an item**: Default `on`.
- **Colour shift on bounce**: the colours rotate a step on every bounce. A
  corner hits two edges, so it shifts two steps. Default `on`.
- **Item ID**: the item whose sprite bounces. An ID with no item falls back
  to the rubber chicken. Default `4566`.
- **Opacity**: 10 to 100%. Default `100%`.
- **Size (px)**: width, 24 to 512; height follows the aspect ratio. Item
  sprites are 36x32, so larger sizes are scaled up and look blurry. Default
  `144`.
- **Speed**: `Ultra slow` to `Ultra fast`, 15 to 600 px/s per axis. Travel
  is at 45 degrees, so about 1.4x that along the diagonal. Default
  `Classic` (180).

**Custom image**

- **Bounce a custom image**: Default `off`.
- **Colour shift on bounce**: as for the item. Default `on`.
- **Custom image file**: a PNG, JPG, GIF or BMP file name inside your
  `.runelite/plugin-data/dvd-bounce` folder, which is created when the
  plugin starts, e.g. `logo.png`. A name that cannot be read bounces a
  notice saying so, plus one chat line naming the file. Default blank.
- **Opacity**: as for the item. Default `100%`.
- **Reload image file**: re-reads the file and applies a name you just
  typed. Ticking or unticking both trigger it. After replacing a file under
  the same name, use it to read the file again.
- **Size (px)**: as for the item. Default `144`.
- **Speed**: as for the item. Default `Classic`.

Animated GIFs loop continuously. Frames are downscaled to at most 512 px on
their longest side and animations are cut to the first 30 frames. A GIF that
declares a canvas larger than 2048x2048 loads as a single frame, and any
source above 16 million pixels (4096x4096) shows the notice instead.
