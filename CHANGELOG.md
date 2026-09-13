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
  events.
- Helpers in `glimmer-uikit.ffi` for system frameworks, Objective-C blocks,
  timers, URLs, the bundle version and SF Symbols.
