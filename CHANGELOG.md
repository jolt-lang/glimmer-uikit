# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-09-24

### Added

- A prop a widget does not know is reported, once per tag and prop, naming
  both. A typo, or a name that changed between versions, no longer renders a
  wrong view in silence. The view still draws without the prop: a throw inside
  a render leaves a phone with a blank screen.
- `:props` in a widget spec names what that widget takes, beside the props
  every widget takes. `glimmer-uikit.widget/unknown-props` is the pure rule.
  A spec without `:props` is never reported on.

## [0.2.1] - 2026-09-23

### Changed

- glimmer is pinned at v0.1.3, which makes `make-reaction` public. The
  `reaction` macro expands to a call of it in the caller's namespace, and jolt
  0.8.11 refuses a private var of another namespace there
  (jolt-lang/jolt#1113), so with the old pin a `reaction` outside
  `glimmer.ratom` stopped compiling.

## [0.2.0] - 2026-09-19

### Added

- Three `objc_msgSend` bindings that return a pointer, for shapes the table
  already had as `:void`: `glimmer-uikit.ffi/objc-msg-send-2p`,
  `objc-msg-send-3p` and `objc-msg-send-1p1i64`. An app that fetches over the
  network sends `dataTaskWithRequest:completionHandler:`,
  `sessionWithConfiguration:delegate:delegateQueue:` and
  `initWithData:encoding:` through them.

### Changed

- The repository lives at jolt-lang/glimmer-uikit, and the changelog's links
  point there.

## [0.1.0] - 2026-09-13

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
- `:date-format` on a label with `:date` and a button with `:label-date`:
  `{:date style :time style}`, where a style is `:none`, `:short`, `:medium`,
  `:long` or `:full`, as in Foundation's `DateFormatter.Style`. The default is
  `{:date :medium :time :none}`, which is how dates showed before.
- `glimmer-uikit.widget/date-styles`, the pure map from `:date-format` to the
  two `NSDateFormatterStyle` values.

### Changed

- glimmer is pinned at v0.1.1. Its reconciler disposes the component watchers
  under a native element that it replaces with one of another tag, so they no
  longer render into a released view.
- The label prop `:date-style` is now `:date-markup`. It holds Pango span
  attributes, and the old name read like `DateFormatter`'s `dateStyle`.
- `glimmer-uikit.ffi/format-date` takes the date style and the time style, and
  keeps one formatter for each pair.
- One registry per view in `glimmer-uikit.widget` replaces nine, so `forget!`
  clears a view with one `dissoc`.
- The colour parsers, the four pin helpers, the three runtime classes, the
  framework paths, the app loop's failure logging, the three find-or-make
  caches in `glimmer-uikit.ffi` and the view registry's updates each share one
  implementation.

### Removed

- `glimmer-uikit.widget/show!` and the `:visible` prop it read. glimmer's
  backend contract never calls it, so `:visible` did nothing.

### Fixed

- `jolt lint` reports 0 warnings again. The test runner's hook on jolt's
  `clojure.test/err!` no longer shows as an unresolved var.
- `glimmer-uikit.ffi/color-hex-alpha` reads `#rgb` as `color-hex` does. It
  threw before.
- The README said to wire a new widget's events in `:ctor`. `create!` forgets
  the view's address after `:ctor`, so a handler stored there was lost.
- `jolt test` fails when a test namespace does not load. The runner printed the
  error but reported 0 failed and exited 0, so CI passed.
- A prop that a later render leaves out is reset on the reused view. A label
  kept its `:markup` colour and size, and a button kept its `:border`.
- The README names every prop the code reads: `:margin-left` and
  `:margin-right` on a box, and the `:background` option of `ui/run`.
- Comments and docstrings no longer refer to the app this backend came from.

[Unreleased]: https://github.com/jolt-lang/glimmer-uikit/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/jolt-lang/glimmer-uikit/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/jolt-lang/glimmer-uikit/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/jolt-lang/glimmer-uikit/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/jolt-lang/glimmer-uikit/releases/tag/v0.1.0
