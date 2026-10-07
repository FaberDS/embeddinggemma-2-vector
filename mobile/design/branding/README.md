# Pocket Ask icon

The selected Quiet P concept combines a rounded P with a speech-bubble counter.
The production SVG redraws the selected draft with clean curves and solid colors:
sage `#4C6758` and ivory `#FAFBF8`, matching the application theme.

- `pocket-ask.svg`: opaque square master.
- `pocket-ask-mark.svg`: transparent vector foreground.
- `google-play-icon.png`: 512 × 512 sRGB, opaque, 32-bit PNG store artwork.
- iOS: `../../iosApp/Assets.xcassets/AppIcon.appiconset`, with a 1024 × 1024
  opaque RGB icon. Xcode generates the smaller sizes; the catalog is included
  in the application's Resources build phase.
- Android: `../../androidApp/src/main/res/drawable/ic_launcher_foreground.xml`
  contains the same path, scaled around its center into the adaptive safe area.
  `ic_launcher_background.xml` supplies the solid background. The adaptive
  launcher icon also reuses the foreground as its monochrome layer.

Source art has no outer corner mask or shadows. The platforms apply their own
icon shape. The initial three built-in image_gen drafts and generation prompts
are preserved in `../logo-drafts`.

To regenerate raster exports, render `pocket-ask.svg` at 1024 px for iOS
(RGB, no alpha) or 512 px for Google Play (RGBA, fully opaque).
Keep the SVG path and Android vector path in sync when changing the mark.

Design references: [Apple app icons](https://developer.apple.com/design/human-interface-guidelines/app-icons),
[Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive),
and [Google Play icon specifications](https://developer.android.com/distribute/google-play/resources/icon-design-specifications).
