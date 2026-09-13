# glimmer-uikit

The **UIKit** backend for [glimmer](https://github.com/jolt-lang/glimmer), the
reactive Clojure(-like) UI framework for [jolt](https://github.com/jolt-lang/jolt).
Where [glimmer-appkit](https://github.com/jolt-lang/glimmer-appkit) renders into
macOS windows, glimmer-uikit renders the same hiccup into real iPhone views —
`UIStackView`/`UILabel`/`UIButton`, driven through the Objective-C runtime by a
plain C FFI (no bridging headers, no Objective-C source).

The whole toolkit fits in three namespaces. glimmer owns the reactive core
(ratom, component model, reconciler) and knows nothing about UIKit; this project
supplies the views, the prop/event wiring and the app loop, and registers them
through `glimmer.backend`.

```clojure
(ns myapp
  (:require [glimmer.ratom :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-uikit.core]))              ; installs the UIKit backend

(defn counter []
  (let [count (atom 0)]
    (fn []
      [:vbox {:spacing 12}
       [:label {:label (str "Count: " @count)}]
       [:hbox {:spacing 8}
        [:button {:label "- 1" :on-click #(swap! count dec)}]
        [:button {:label "+ 1" :on-click #(swap! count inc)}]
        [:button {:label "reset" :on-click #(reset! count 0)}]]])))

(defn -main [& _]
  (ui/run counter))
```

Components, reactive state and reconciliation are documented in glimmer's README.
What follows is the UIKit-specific part: what you can put in the hiccup.

## Requirements

macOS with Xcode, for the simulator, the iOS SDKs and `clang`. jolt 0.8.0 or
newer: `jolt.ffi/write` changed its argument order in 0.8.0, and an older runtime
writes to the wrong place rather than failing, so `deps.edn` declares the floor.

An iOS app is cross-compiled, so jolt also needs a target pack for each machine
type: `tarm64ios` for the simulator, and `tpb64l` (portable bytecode, foreign
calls through libffi, since iOS forbids generating code at run time) for a phone.
[glimmer-ios-demo](https://github.com/statonjr/glimmer-ios-demo) has the recipe
for both. `scripts/bundle` looks for them at `~/dev/pack-tarm64ios-sim` and
`~/dev/pack-tpb64l-ios`, or wherever `PACK_SIM` and `PACK_DEVICE` point.

UIKit is loaded by `glimmer-uikit.ffi/load-uikit!` from inside the app loop, never
at require time. Nothing is declared under `:jolt/native`: `jolt build` loads
every namespace on the macOS host before it emits code, and the headless test
suite (`jolt -M:test`) runs on Linux CI, where no Objective-C runtime exists.

## Running

```sh
jolt test      # unit tests (Pango markup, prop normalization, handler reuse — no device needed)
jolt lint      # clj-kondo over src, test and examples
jolt bundle    # cross-compile the counter into build/Counter.app (TARGET=sim, or TARGET=device)
jolt install   # install it on the booted simulator
jolt launch    # launch it; `jolt console` launches it with stdout attached
jolt smoke     # the counter, tapped twice from a worker thread (needs a booted simulator)
```

`NS=<namespace> jolt bundle` bundles another example. A phone build must be
signed before it installs; glimmer-ios-demo shows how.

## Hiccup reference

Elements are `[:tag props? & children]`. `props` is an optional map; children may
be native elements, component invocations (`[my-component arg]`), strings, or
numbers (rendered as labels). `nil` children are skipped.

**Containers:** `:box` (`:orientation :horizontal|:vertical`), `:hbox`, `:vbox`,
`:layers` (children fill it edge to edge, back to front), `:scroll` (children
stack vertically and scroll; never sideways).

There is no `:window` tag to write. The root of the tree mounts into the root
view controller's view, pinned to the safe area.

**Leaf widgets:** `:button`, `:checkbutton`, `:label`, `:image`, `:gradient`.

**Common props (apply to every widget):**

- `:halign` — one of `:fill :start :end :center`; `:valign` — one of
  `:fill :top :bottom :center` (translated to the parent stack's alignment
  across its axis; the last child that names one wins)
- `:hexpand`/`:vexpand` — boolean (lowers the content-hugging priority so the
  stack stretches the widget along that axis, and restores it when the prop goes)
- `:width`/`:height` — points
- `:background` — `"#rrggbb"`; `:alpha` — 0.0–1.0
- `:center-y` — `true`, or an offset in points (see [Layout](#layout))
- `:height-anchor`/`:height-like` — a group name (see [Layout](#layout))
- On the root: `:vfill`, `:full-bleed`; on a child of `:layers`: `:safe`
  (see [Layout](#layout))

**Per-tag props:**

- Box: `:orientation`, `:spacing`, `:homogeneous`, `:margin` (all four sides),
  or `:margin-start`/`:margin-end`/`:margin-top`/`:margin-bottom`
- Button: `:label`, `:sensitive`, `:foreground`, `:font-size`, `:font-weight`
  (`:bold`), `:radius`, `:border` (`[width "#rrggbb" alpha]`), `:padding` (the
  title's left and right inset, in points), `:xalign` (0.0–1.0), `:label-date`
  (milliseconds, shown as a date before `:label`, formatted by `:date-format`).
  A title too long for its button loses its tail.
- Checkbutton: `:active`, `:symbol` (the checkmark's point size, default 24),
  `:foreground` (the checkmark's colour), and a button's props for the box
- Label: `:label`/`:text`, `:markup` (Pango markup), `:xalign` (0.0–1.0),
  `:wrap` (any value wraps), `:lines` (int), `:ellipsize` (`:start`/`:middle`/`:end`),
  `:date` (milliseconds, shown as a date in the phone's locale), `:date-format`,
  `:date-markup` (span attributes for `:date`)
- Image: `:src` — a file in the app bundle, aspect-filled and clipped
- Gradient: `:stops` — `[["#rrggbb" alpha location] ...]`, top to bottom
- Scroll: `:spacing`

**Dates:** `:date-format` is `{:date style :time style}`, on a label with
`:date` or a button with `:label-date`. A style is `:none`, `:short`,
`:medium`, `:long` or `:full`, the names of Foundation's
[`DateFormatter.Style`](https://developer.apple.com/documentation/foundation/dateformatter/style).
A missing key keeps its default, `{:date :medium :time :none}`. The phone's
locale decides how each style reads. A style that is not one of the five
throws.

UIKit has no checkbox control; AppKit's `NSButton` is one, which is why
glimmer-appkit's `:checkbutton` can wrap it. Here a `:checkbutton` is a button
with no title whose tile is the box: empty when inactive, and with an SF Symbol
checkmark centred in it when `:active`. The props and the event are
glimmer-appkit's, so the same hiccup works on both backends.

**Events:**

- `:on-click` — button tapped. Handler takes no args.
- `:on-toggled` — checkbutton tapped. Handler takes no args.

Taps route through `addTarget:action:forControlEvents:` to a single dynamic ObjC
class (`GlimmerTarget`) whose IMP is a jolt `foreign-callable`. The target is
added once at mount, but the handler behind it is replaced on every render (see
[View reuse](#view-reuse)). A handler owns the state: `:on-toggled` flips the
cell the component reads, and `:active` comes back down as a prop. UIKit does
not fire actions for programmatic property changes, so a re-render can't feed
back into its own handler.

## Pango markup

A label's `:markup` prop takes a Pango markup string, or hiccup data that is
validated and serialized for you — the same vocabulary glimmer-gtk and
glimmer-appkit use, so the same tree works on all three:

```clojure
[:label {:markup [:span {:foreground "#8e939d"} "Nothing to do yet"]}]
[:label {:markup [:b [:i "bold italic"]]}]
```

Pango's markup is a small XML subset, not HTML, so the data is checked against
Pango's own vocabulary first: an HTML-only tag (`:div`, `:br`) or a typo'd
attribute throws at the call site instead of rendering silently wrong. Attribute
names use Pango's spelling with underscores (`:font_family`, `:letter_spacing`),
and text is escaped for you. The string comes from this project's own walk over
the data, which the test suite checks byte for byte against hiccup's output, so
an app does not carry hiccup at run time.

The validated string is parsed back into segments and applied as an
`NSAttributedString`. What survives: `:b`, `:i`, `:s` and `:u`; and on a
`:span`, `:foreground`, `:size`, `:weight "bold"`, `:strikethrough "true"`,
`:underline "true"`, `:variant "smallcaps"`, `:font_family` (a PostScript name;
one iOS lacks falls back to the system font), and `:letter_spacing` (Pango units,
1/1024 of a point, negative allowed).

## Layout

A stack cannot express everything a phone screen needs, so a few props reach
past it.

- **The safe area.** The root is pinned to the safe area's top, leading and
  trailing edges, and hugs its content at the bottom. `:vfill` pins its bottom
  too, so a `:vexpand` child can push a footer down; `:full-bleed` pins it to the
  window's edges instead, under the status bar. A child of `:layers` fills its
  parent's edges, or its safe area with `:safe`.
- **The centre of the screen.** `:center-y` pins a widget's centre to its parent
  stack's centre, and the spacers around it take whatever heights make that true.
- **Two views, one height.** `:height-anchor g` names a view, and a later
  `:height-like g` in the same render takes its height. Two spacers share the
  slack that way, which a stack's fill distribution cannot do alone.

## View reuse

glimmer reuses a view when the tag at the same position matches across renders,
including across screens. Every piece of state this backend keeps follows the
render, not the mount:

- A prop that a later render leaves out, or sets to `nil`, is reset. The reused
  view then looks and behaves like a new view of that tag with the later
  render's props. `:vfill`, `:full-bleed`, `:safe`, `:height-anchor` and
  `:height-like` are the exception: they act only when a view joins its parent.
- The handler behind `:on-click` and `:on-toggled` is replaced on every render.
  Otherwise a reused button fires the handler of the screen it was built for.
- `:width`, `:height` and `:center-y` keep one constraint per view and kind:
  added when the prop appears, replaced when it changes, dropped when it goes.
- `:hexpand`/`:vexpand` put the hugging priority back to UIKit's default when the
  prop goes.
- The registries are keyed by view address, and a new view can be allocated where
  a freed one was. Creating a view forgets everything known about its address.

## Lifecycle

`glimmer-uikit.core/on-lifecycle!` registers one zero-arg handler per event:
`:resign-active`, `:background`, `:foreground`, `:active`, `:terminate`. `nil`
removes it. Each event is also logged to stdout with a timestamp, which is what
`jolt console` shows.

## Extending the widget set

A consumer can teach glimmer-uikit new hiccup tags at load time:

```clojure
(require '[glimmer-uikit.widget :as w])

(w/register-widget! :my-thing
  {:ctor      (fn [props] (make-the-view props))
   :apply     (fn [widget props] (apply props at mount and on re-render))
   :connect   (fn [widget props] (wire its events))   ; optional
   :container :none})          ; or :box / :layers / :scroll

(w/register-signal! :on-input "value-changed")   ; apply-props! now skips :on-input
```

`register-signal!` marks a key as an event, so a re-render does not try to set it
on the view. The new widget wires the event to UIKit in `:connect`. `create!`
calls it once, last, after it wires `:on-click` and `:on-toggled`. Do not wire
events in `:ctor`: `create!` forgets the view's address after `:ctor` returns.

`:connect` runs only at mount, as in glimmer-appkit. When glimmer reuses the
view for a later render, the handler that `:connect` wired stays. Only
`:on-click` and `:on-toggled` take the new handler on each render.

## Architecture

Three namespaces:

- **`glimmer-uikit.ffi`** — the Objective-C runtime and UIKit, through `jolt.ffi`:
  `objc_getClass`/`sel_registerName`, `objc_msgSend` bound at fixed arities
  (struct args flattened into doubles — a `CGRect` or `UIEdgeInsets` is an HFA of
  four doubles, so a flattened call passes exactly the registers a real method
  expects; never `:varargs`, which shifts FP args onto the stack and corrupts
  them), the CFRunLoop pieces the scheduler needs, and `UIApplicationMain`. Beyond
  the views, it has what an app soon wants: `load-framework!` and `data-symbol`
  for Core Location or MapKit, `make-block` for a completion handler,
  `nsvalue->doubles` and `value-for-key` for a struct property, `timer!`,
  `open-url!`, `bundle-version` and `system-image`. `BOOL` returns are `:uint8`,
  because jolt's `:char` is a Scheme character. Nothing runs at load.
- **`glimmer-uikit.widget`** — hiccup to UIKit: tag to constructor, props to
  setters, `:on-*` to target/action (a dynamic `GlimmerTarget` ObjC class whose
  IMP is a jolt `foreign-callable`), Pango markup to `NSAttributedString`, and
  container child management. The tag and signal registries are open
  (`register-widget!`, `register-signal!`).
- **`glimmer-uikit.core`** — the backend map handed to `glimmer.backend/register!`,
  the app loop (a `GlimmerAppDelegate` class registered at run time, handed by
  name to `UIApplicationMain`, which mounts the root in
  `application:didFinishLaunchingWithOptions:`), the lifecycle hooks, and the
  scheduler that marshals off-main-thread work onto the main loop via a
  `CFRunLoopSource` — the UIKit analogue of GTK's `g_idle_add`, without
  libdispatch or blocks.

## Live development

`ui/run` never returns on iOS: `UIApplicationMain` owns the main thread for the
life of the app. A ratom written from another thread (a `future`, an nREPL eval)
repaints through the scheduler, and `(glimmer.core/reload!)` re-mounts the root
in the same view after components are redefined.

This project does not start an nREPL server. glimmer-ios-demo's
[counter example](https://github.com/statonjr/glimmer-ios-demo/blob/main/examples/counter/README.org)
runs one inside the app, and its "Live" section shows how to reach it on the
simulator and, over USB, on a phone.

## License

MIT (see `LICENSE`).
