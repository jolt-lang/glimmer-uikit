# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The UIKit backend for glimmer. Requiring `glimmer-uikit.core` installs it, and
  `glimmer.core/run` starts the app.
- Containers: `:box`, `:hbox`, `:vbox`, `:layers` and `:scroll`.
- Widgets: `:button`, `:checkbutton`, `:label`, `:image` and `:gradient`.
- Events: `:on-click` and `:on-toggled`.
- Pango markup on a label's `:markup` prop, as a string or as hiccup data that is
  validated against Pango's vocabulary.
- Layout props for a phone screen: `:vfill`, `:full-bleed` and `:safe` for the
  safe area, `:center-y`, and `:height-anchor` with `:height-like`.
- Dates formatted by the phone: `:date` on a label, `:label-date` on a button.
- `glimmer-uikit.core/on-lifecycle!` for the app's lifecycle events, and
  `glimmer-uikit.core/schedule!` to run work on the main thread.
- `glimmer-uikit.widget/register-widget!` and `register-signal!` to add tags and
  events. A widget spec MAY carry `:connect (fn [widget props])`, which
  `create!` calls once, last, to wire the widget's own events. This follows
  glimmer-appkit.
- Helpers in `glimmer-uikit.ffi` for system frameworks, Objective-C blocks,
  timers, URLs, the bundle version and SF Symbols.
- Pure helpers the host can test: `glimmer-uikit.ffi/hex->rgb`,
  `glimmer-uikit.ffi/framework-path` and `glimmer-uikit.widget/xalign->side`.
- `glimmer-uikit.ffi/ensure-class!` to find or register an Objective-C class,
  and `glimmer-uikit.ffi/pin-attrs!` to pin a view's edges to another item.

### Changed

- One registry per view in `glimmer-uikit.widget` replaces nine, so `forget!`
  clears a view with one `dissoc`.
- The colour parsers, the four pin helpers, the three runtime classes, the
  framework paths and the app loop's failure logging each share one
  implementation.

### Fixed

- `jolt lint` reports 0 warnings again. The test runner's hook on jolt's
  `clojure.test/err!` no longer shows as an unresolved var.
- `glimmer-uikit.ffi/color-hex-alpha` reads `#rgb` as `color-hex` does. It
  threw before.
- The README said to wire a new widget's events in `:ctor`. `create!` forgets
  the view's address after `:ctor`, so a handler stored there was lost.
