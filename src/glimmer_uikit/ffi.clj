(ns glimmer-uikit.ffi
  "Raw bindings for the Objective-C runtime and UIKit, built on jolt.ffi.

  Objective-C from a C FFI: classes come from objc_getClass, selectors from
  sel_registerName, and every method call goes through objc_msgSend bound at a
  concrete arity. On Apple arm64 objc_msgSend is a dispatcher that forwards the
  caller's registers to the method implementation, so a FIXED-arity binding
  whose struct args are flattened into their component doubles matches the real
  call layout (a CGRect or UIEdgeInsets is an HFA of 4 doubles -> d0-d3). Never
  bind it with :varargs — the variadic convention passes FP args on the stack.

  UIKit is dlopen'd by load-uikit!, which the app loop calls from -main: never at
  namespace load, because jolt build loads this namespace on the macOS host
  first, and the headless tests run where there is no ObjC runtime at all.

  Marshalling: a C string crosses the FFI as :string (UTF-8); NSStrings are
  created with stringWithUTF8String: and read back with UTF8String. BOOL args
  are :int, BOOL returns :uint8 (jolt's :char is a Scheme character),
  NSInteger/NSUInteger :int64, CGFloat :double, UILayoutPriority :float."
  (:require [jolt.ffi :as ffi]))

;; --- loading UIKit (from -main, never at load) ------------------------------
(defonce ^:private uikit-loaded? (atom false))

(defn load-uikit!
  "dlopen UIKit so objc_getClass finds its classes. Idempotent. Only meaningful
  inside the simulator/device process; on the host it throws, which is the
  point — nothing calls it there."
  []
  (when-not @uikit-loaded?
    (ffi/load-library "/System/Library/Frameworks/UIKit.framework/UIKit")
    (reset! uikit-loaded? true))
  nil)

;; --- constants ---------------------------------------------------------------
;; UILayoutConstraintAxis
(def AXIS-HORIZONTAL 0)
(def AXIS-VERTICAL 1)
;; UIStackViewDistribution
(def DISTRIBUTION-FILL 0)
(def DISTRIBUTION-FILL-EQUALLY 1)
;; UIStackViewAlignment (leading == top, trailing == bottom)
(def ALIGN-FILL 0) (def ALIGN-LEADING 1) (def ALIGN-CENTER 3) (def ALIGN-TRAILING 4)
;; NSLayoutAttribute (same numbering as AppKit)
(def ATTR-LEFT 1)  (def ATTR-RIGHT 2)  (def ATTR-TOP 3) (def ATTR-BOTTOM 4)
(def ATTR-LEADING 5) (def ATTR-TRAILING 6)
(def ATTR-WIDTH 7) (def ATTR-HEIGHT 8)
(def ATTR-CENTER-X 9) (def ATTR-CENTER-Y 10)
(def RELATION-EQUAL 0)
;; NSTextAlignment
(def TEXT-ALIGN-LEFT 0) (def TEXT-ALIGN-CENTER 1) (def TEXT-ALIGN-RIGHT 2)
;; NSLineBreakMode
(def LINE-BREAK-WRAP 0) (def LINE-BREAK-CLIP 2)
(def LINE-BREAK-HEAD 3) (def LINE-BREAK-TAIL 4) (def LINE-BREAK-MIDDLE 5)
;; UILayoutPriority (a float)
(def PRIORITY-REQUIRED 1000)
(def PRIORITY-LOW 250)
(def PRIORITY-VERY-LOW 1)
;; UIButtonType / UIControlState / UIControlEvents
(def BUTTON-SYSTEM 1)
(def CONTROL-STATE-NORMAL 0)
(def EVENT-TOUCH-UP-INSIDE 64)                 ; 1 << 6
;; UIFontWeight (a CGFloat)
(def FONT-WEIGHT-REGULAR 0.0)
(def FONT-WEIGHT-BOLD 0.4)
;; NSAttributedString attribute keys — the constants ARE these strings, on iOS too
(def NS-FONT-ATTR "NSFont")
(def NS-FOREGROUND-COLOR-ATTR "NSColor")
(def NS-STRIKETHROUGH-ATTR "NSStrikethrough")
(def NS-UNDERLINE-ATTR "NSUnderline")

;; --- ObjC runtime ------------------------------------------------------------
;; objc_msgSend bound at the arities the backend needs. Each is a distinct
;; foreign-procedure over the same symbol; the dispatcher forwards registers
;; verbatim, so the typed shape only has to match the method's calling
;; convention, not its declared prototype.
(ffi/defcfn objc-msg-send-0        "objc_msgSend" [:pointer :pointer] :pointer)
(ffi/defcfn objc-msg-send-0void    "objc_msgSend" [:pointer :pointer] :void)
(ffi/defcfn objc-msg-send-0i64     "objc_msgSend" [:pointer :pointer] :int64)
(ffi/defcfn objc-msg-send-0cstr    "objc_msgSend" [:pointer :pointer] :string)
(ffi/defcfn objc-msg-send-1p       "objc_msgSend" [:pointer :pointer :pointer] :pointer)
(ffi/defcfn objc-msg-send-1pvoid   "objc_msgSend" [:pointer :pointer :pointer] :void)
(ffi/defcfn objc-msg-send-1s       "objc_msgSend" [:pointer :pointer :string] :pointer)
(ffi/defcfn objc-msg-send-1d       "objc_msgSend" [:pointer :pointer :double] :pointer)
(ffi/defcfn objc-msg-send-1dvoid   "objc_msgSend" [:pointer :pointer :double] :void)
(ffi/defcfn objc-msg-send-1i64     "objc_msgSend" [:pointer :pointer :int64] :pointer)
(ffi/defcfn objc-msg-send-1i64void "objc_msgSend" [:pointer :pointer :int64] :void)
;; BOOL args are :int — jolt's :char is a Scheme CHARACTER, not an 8-bit int.
(ffi/defcfn objc-msg-send-1intvoid "objc_msgSend" [:pointer :pointer :int] :void)
;; setContentHuggingPriority:forAxis: — UILayoutPriority is a FLOAT
(ffi/defcfn objc-msg-send-1f1i64void "objc_msgSend" [:pointer :pointer :float :int64] :void)
;; systemFontOfSize:weight: — (CGFloat, UIFontWeight) -> UIFont*
(ffi/defcfn objc-msg-send-2d       "objc_msgSend" [:pointer :pointer :double :double] :pointer)
(ffi/defcfn objc-msg-send-4d       "objc_msgSend" [:pointer :pointer :double :double :double :double] :pointer)
;; setLayoutMargins: (UIEdgeInsets top left bottom right, an HFA of 4 doubles)
(ffi/defcfn objc-msg-send-4dvoid   "objc_msgSend" [:pointer :pointer :double :double :double :double] :void)
;; setTitle:forState: / insertArrangedSubview:atIndex:
(ffi/defcfn objc-msg-send-1p1i64void "objc_msgSend" [:pointer :pointer :pointer :int64] :void)
(ffi/defcfn objc-msg-send-0int     "objc_msgSend" [:pointer :pointer] :int)   ; a 32-bit enum back, CLAuthorizationStatus (1.2)
(ffi/defcfn objc-msg-send-1d3p1int "objc_msgSend" [:pointer :pointer :double :pointer :pointer :pointer :int] :pointer)   ; NSTimer's scheduled…repeats: (1.2)
;; addTarget:action:forControlEvents: — (id target, SEL action, UIControlEvents)
(ffi/defcfn objc-msg-send-2p1i64void "objc_msgSend" [:pointer :pointer :pointer :pointer :int64] :void)
;; (id, SEL, id) -> NSInteger (indexOfObject:)
(ffi/defcfn objc-msg-send-1p-i64ret "objc_msgSend" [:pointer :pointer :pointer] :int64)
;; addAttribute:value:range: — (id key, id value, NSUInteger loc, NSUInteger len)
(ffi/defcfn objc-msg-send-2p2i64void "objc_msgSend" [:pointer :pointer :pointer :pointer :int64 :int64] :void)
;; constraintWithItem:attribute:relatedBy:toItem:attribute:multiplier:constant:
(ffi/defcfn objc-msg-send-constraint
  "objc_msgSend" [:pointer :pointer :pointer :int64 :int64 :pointer :int64 :double :double] :pointer)

(ffi/defcfn objc-get-class           "objc_getClass"           [:string] :pointer)
(ffi/defcfn sel-register-name        "sel_registerName"        [:string] :pointer)
(ffi/defcfn objc-allocate-class-pair "objc_allocateClassPair"  [:pointer :string :size_t] :pointer)
(ffi/defcfn objc-register-class-pair "objc_registerClassPair"  [:pointer] :void)
(ffi/defcfn class-add-method         "class_addMethod"         [:pointer :pointer :pointer :string] :uint8)
(ffi/defcfn c-dlopen "dlopen" [:string :int] :pointer)      ; a framework by path (1.2)
(ffi/defcfn c-dlsym  "dlsym"  [:pointer :string] :pointer)  ; a data symbol, which no defcfn can name
(def RTLD-NOW 2)

;; int UIApplicationMain(int argc, char *argv[], NSString *principal, NSString *delegate)
;; Never returns; :blocking so the parked jolt thread does not pin the collector.
(ffi/defcfn ui-application-main "UIApplicationMain" [:int :pointer :pointer :pointer] :int :blocking)

;; --- CFRunLoop (scheduling onto the main loop, pure C — no blocks needed) ----
(ffi/defcfn cf-run-loop-get-main      "CFRunLoopGetMain"      [] :pointer)
(ffi/defcfn cf-run-loop-source-create "CFRunLoopSourceCreate" [:pointer :int64 :pointer] :pointer)
(ffi/defcfn cf-run-loop-add-source    "CFRunLoopAddSource"    [:pointer :pointer :pointer] :void)
(ffi/defcfn cf-run-loop-source-signal "CFRunLoopSourceSignal" [:pointer] :void)
(ffi/defcfn cf-run-loop-wake-up       "CFRunLoopWakeUp"       [:pointer] :void)
;; CFRunLoopAddSource hashes the mode string and does NOT accept NULL. Create the
;; default-mode string once (kCFRunLoopCommonModes must NOT be reproduced this
;; way — CF recognizes that one by identity). UIApplicationMain runs the main
;; loop in the default mode.
(ffi/defcfn cf-string-create-with-cstring
  "CFStringCreateWithCString" [:pointer :string :int64] :pointer)

(def ^:private kCFRunLoopDefaultMode
  ;; a delay, not a def: nothing calls CoreFoundation at namespace load
  (delay (cf-string-create-with-cstring ffi/null "kCFRunLoopDefaultMode" 134217984))) ; kCFStringEncodingUTF8

(defn default-mode
  "The CFString naming the run loop's default mode (retained by the loop)."
  [] @kCFRunLoopDefaultMode)

;; --- selector / class caches -------------------------------------------------
(def ^:private sel-cache (atom {}))
(defn sel
  "Register (once) and return the selector for a method name."
  [name]
  (or (get @sel-cache name)
      (let [s (sel-register-name name)]
        (swap! sel-cache assoc name s)
        s)))

(def ^:private class-cache (atom {}))
(defn cls
  "Look up (once) and return the ObjC class for a name."
  [name]
  (or (get @class-cache name)
      (let [c (objc-get-class name)]
        (swap! class-cache assoc name c)
        c)))

(defn new-obj
  "[[Class alloc] init]."
  [class-name]
  (objc-msg-send-0 (objc-msg-send-0 (cls class-name) (sel "alloc")) (sel "init")))

;; --- strings ----------------------------------------------------------------
(defn nsstring
  "Create an NSString (autoreleased) from a jolt string."
  [s]
  (objc-msg-send-1s (cls "NSString") (sel "stringWithUTF8String:") s))

(defn nsstring->str
  "Read an NSString as a jolt string, or nil when the pointer is null."
  [s]
  (when-not (ffi/null? s)
    (objc-msg-send-0cstr s (sel "UTF8String"))))

(defn number-int
  "An NSNumber wrapping an integer (for attributed-string attribute values)."
  [n]
  (objc-msg-send-1i64 (cls "NSNumber") (sel "numberWithInt:") n))

;; --- UIApplication / UIWindow / UIViewController -----------------------------
(defn run-application!
  "UIApplicationMain with a delegate CLASS NAME. Blocks for the life of the
  process; UIKit instantiates the delegate itself."
  [delegate-class-name]
  (ffi/with-c-string-array [argv 1] ["glimmer"]
    (ui-application-main 1 argv ffi/null (nsstring delegate-class-name))))
(defn window-new
  "[[UIWindow alloc] init] — since iOS 13, sized to the main screen."
  [] (new-obj "UIWindow"))
(defn window-root-controller! [w vc] (objc-msg-send-1pvoid w (sel "setRootViewController:") vc))
(defn window-make-key! [w] (objc-msg-send-0void w (sel "makeKeyAndVisible")))
(defn view-controller-new [] (new-obj "UIViewController"))
(defn controller-view [vc] (objc-msg-send-0 vc (sel "view")))

;; --- UIView layout ----------------------------------------------------------
(defn add-subview! [parent child] (objc-msg-send-1pvoid parent (sel "addSubview:") child))
(defn remove-from-superview! [v] (objc-msg-send-0void v (sel "removeFromSuperview")))
(defn set-translates-autoresizing! [v b]
  (objc-msg-send-1intvoid v (sel "setTranslatesAutoresizingMaskIntoConstraints:") (if b 1 0)))
(defn set-hidden! [v b] (objc-msg-send-1intvoid v (sel "setHidden:") (if b 1 0)))
(defn set-background! [v color] (objc-msg-send-1pvoid v (sel "setBackgroundColor:") color))
(defn set-hugging! [v priority axis]
  ;; UILayoutPriority is a FLOAT (32-bit), not CGFloat.
  (objc-msg-send-1f1i64void v (sel "setContentHuggingPriority:forAxis:") (float priority) axis))
(defn safe-area-guide [v] (objc-msg-send-0 v (sel "safeAreaLayoutGuide")))
(defn constraint [item1 attr1 item2 attr2 multiplier constant]
  (objc-msg-send-constraint
    (cls "NSLayoutConstraint")
    (sel "constraintWithItem:attribute:relatedBy:toItem:attribute:multiplier:constant:")
    item1 attr1 RELATION-EQUAL item2 attr2 (double multiplier) (double constant)))
(defn activate! [c] (objc-msg-send-1intvoid c (sel "setActive:") 1))
(defn pin-to-safe-area!
  "Pin `child`'s top/leading/trailing to `parent`'s safe-area layout guide. The
  bottom is left free: a stack hugs its content."
  [child parent]
  (set-translates-autoresizing! child false)
  (let [guide (safe-area-guide parent)]
    (doseq [a [ATTR-TOP ATTR-LEADING ATTR-TRAILING]]
      (activate! (constraint child a guide a 1.0 0.0)))))

;; --- UIStackView ------------------------------------------------------------
(defn stack-new
  "An empty UIStackView (alloc/init — there is no stackViewWithViews: on iOS)."
  [] (new-obj "UIStackView"))
(defn stack-axis [s] (objc-msg-send-0i64 s (sel "axis")))
(defn stack-axis! [s a] (objc-msg-send-1i64void s (sel "setAxis:") a))
(defn stack-spacing! [s d] (objc-msg-send-1dvoid s (sel "setSpacing:") (double d)))
(defn stack-alignment! [s a] (objc-msg-send-1i64void s (sel "setAlignment:") a))
(defn stack-distribution! [s d] (objc-msg-send-1i64void s (sel "setDistribution:") d))
(defn stack-layout-margins!
  "Edge insets — and the flag without which UIStackView ignores them."
  [s top left bottom right]
  (objc-msg-send-4dvoid s (sel "setLayoutMargins:")
                        (double (or top 0)) (double (or left 0))
                        (double (or bottom 0)) (double (or right 0)))
  (objc-msg-send-1intvoid s (sel "setLayoutMarginsRelativeArrangement:") 1))
(defn stack-add-arranged! [s v] (objc-msg-send-1pvoid s (sel "addArrangedSubview:") v))
(defn stack-remove-arranged! [s v] (objc-msg-send-1pvoid s (sel "removeArrangedSubview:") v))
(defn stack-insert-arranged! [s v i] (objc-msg-send-1p1i64void s (sel "insertArrangedSubview:atIndex:") v i))
(defn stack-index-of!
  "The arranged index of `v` (ask the arrangedSubviews array)."
  [s v]
  (objc-msg-send-1p-i64ret (objc-msg-send-0 s (sel "arrangedSubviews")) (sel "indexOfObject:") v))
(defn stack-arranged! [s] (objc-msg-send-0 s (sel "arrangedSubviews")))

;; --- NSArray ----------------------------------------------------------------
(defn array-count [a] (objc-msg-send-0i64 a (sel "count")))
(defn array-get [a i] (objc-msg-send-1i64 a (sel "objectAtIndex:") i))

;; --- UILabel ----------------------------------------------------------------
(defn label-text! [l s] (objc-msg-send-1pvoid l (sel "setText:") (nsstring s)))
(defn label-text [l] (nsstring->str (objc-msg-send-0 l (sel "text"))))
(defn label-new
  "A UILabel with initial text."
  [s]
  (let [l (new-obj "UILabel")]
    (label-text! l s)
    l))
(defn label-attributed! [l a] (objc-msg-send-1pvoid l (sel "setAttributedText:") a))
(defn label-align! [l a] (objc-msg-send-1i64void l (sel "setTextAlignment:") a))
(defn label-lines! [l n] (objc-msg-send-1i64void l (sel "setNumberOfLines:") n))
(defn label-line-break! [l m] (objc-msg-send-1i64void l (sel "setLineBreakMode:") m))

;; --- UIButton / UIControl ---------------------------------------------------
(defn button-title! [b s]
  (objc-msg-send-1p1i64void b (sel "setTitle:forState:") (nsstring s) CONTROL-STATE-NORMAL))
(defn button-new
  "A system-style button with a title (target/action wired later)."
  [title]
  (let [b (objc-msg-send-1i64 (cls "UIButton") (sel "buttonWithType:") BUTTON-SYSTEM)]
    (button-title! b title)
    b))
(defn control-enabled! [c b] (objc-msg-send-1intvoid c (sel "setEnabled:") (if b 1 0)))
(defn add-target!
  "addTarget:action:forControlEvents: — `events` is a UIControlEvents mask."
  [c target selector events]
  (objc-msg-send-2p1i64void c (sel "addTarget:action:forControlEvents:") target selector events))
(defn send-actions!
  "Fire the control's actions for `events` programmatically (smoke tests)."
  [c events]
  (objc-msg-send-1i64void c (sel "sendActionsForControlEvents:") events))

;; --- fonts / colors / attributed strings ------------------------------------
(defn system-font [size weight]
  (objc-msg-send-2d (cls "UIFont") (sel "systemFontOfSize:weight:") (double size) (double weight)))
(defn italic-font-size [size] (objc-msg-send-1d (cls "UIFont") (sel "italicSystemFontOfSize:") (double size)))
(defn system-background-color [] (objc-msg-send-0 (cls "UIColor") (sel "systemBackgroundColor")))
(defn- hex-digit [c]
  (let [n (int c)]
    (cond (<= 48 n 57) (- n 48)
          (<= 97 n 102) (- n 87)
          (<= 65 n 70) (- n 55)
          :else (throw (ex-info (str "glimmer-uikit: bad hex digit " c) {})))))
(defn- hex->int [s] (reduce (fn [acc c] (+ (* acc 16) (hex-digit c))) 0 s))
(defn color-hex
  "Parse \"#rrggbb\" (or \"#rgb\") into a UIColor."
  [hex]
  (let [h (subs hex 1)
        h (if (= 3 (count h)) (apply str (mapcat (fn [c] [c c]) h)) h)]
    (objc-msg-send-4d (cls "UIColor") (sel "colorWithRed:green:blue:alpha:")
                      (/ (hex->int (subs h 0 2)) 255.0)
                      (/ (hex->int (subs h 2 4)) 255.0)
                      (/ (hex->int (subs h 4 6)) 255.0)
                      1.0)))
(defn attributed-new
  "An NSMutableAttributedString over a plain string."
  [s]
  (objc-msg-send-1p (objc-msg-send-0 (cls "NSMutableAttributedString") (sel "alloc"))
                    (sel "initWithString:") (nsstring s)))
(defn attributed-add!
  "Add an attribute over [start, start+len)."
  [a key value start len]
  (objc-msg-send-2p2i64void a (sel "addAttribute:value:range:") (nsstring key) value start len))
(defn attributed-length [a] (objc-msg-send-0i64 a (sel "length")))

;; --- polish (2026-09-05): what a themed screen needs ------------------------
;; setObject:forKey: — (id value, id key); setTitleColor:forState: reuses 1p1i64
(ffi/defcfn objc-msg-send-2pvoid "objc_msgSend" [:pointer :pointer :pointer :pointer] :void)
;; fontWithDescriptor:size: — (UIFontDescriptor*, CGFloat) -> UIFont*
(ffi/defcfn objc-msg-send-1p1d   "objc_msgSend" [:pointer :pointer :pointer :double] :pointer)

(def ATTR-NONE 0)                       ; NSLayoutAttributeNotAnAttribute, for a self-constraint
;; AAT feature type / selector: upper-case letters to small caps (OpenType c2sc)
(def FEATURE-UPPER-CASE 38)
(def SELECTOR-UPPER-SMALL-CAPS 1)

;; UIButton
(defn button-title-color! [b color]
  (objc-msg-send-1p1i64void b (sel "setTitleColor:forState:") color CONTROL-STATE-NORMAL))
(defn button-title-label [b] (objc-msg-send-0 b (sel "titleLabel")))
(defn button-content-insets!
  "The title's inset from the button's edges — UIEdgeInsets top left bottom
  right, the four-double shape stack-layout-margins! sends. #18: a list
  row's text was flush with its tile."
  [b top left bottom right]
  (objc-msg-send-4dvoid b (sel "setContentEdgeInsets:")
                        (double (or top 0)) (double (or left 0))
                        (double (or bottom 0)) (double (or right 0))))
;; UIControlContentHorizontalAlignment: center 0, left 1, right 2 (fill 3)
(def BUTTON-ALIGN-CENTER 0) (def BUTTON-ALIGN-LEFT 1) (def BUTTON-ALIGN-RIGHT 2)
(defn button-horizontal-alignment!
  "Where the title sits in the button's width — a list row's text starts at
  the same x on every row when it is left (#18)."
  [b a]
  (objc-msg-send-1i64void b (sel "setContentHorizontalAlignment:") a))
(defn system-image
  "An SF Symbol by `name` at `points` — UIImage systemImageNamed: under a
  symbol configuration, iOS 13 and up. The null pointer for a name UIKit
  does not know (#3)."
  [name points]
  (let [img (objc-msg-send-1p (cls "UIImage") (sel "systemImageNamed:") (nsstring name))]
    (if (ffi/null? img)
      img
      (objc-msg-send-1p img (sel "imageWithConfiguration:")
                        (objc-msg-send-1d (cls "UIImageSymbolConfiguration")
                                          (sel "configurationWithPointSize:") (double points))))))
(defn button-image! [b img] (objc-msg-send-1p1i64void b (sel "setImage:forState:") img CONTROL-STATE-NORMAL))
(defn set-tint-color! [v color] (objc-msg-send-1pvoid v (sel "setTintColor:") color))
(defn set-alpha! [v a] (objc-msg-send-1dvoid v (sel "setAlpha:") (double a)))
(defn set-font! [v font] (objc-msg-send-1pvoid v (sel "setFont:") font))

;; CALayer
(defn layer [v] (objc-msg-send-0 v (sel "layer")))
(defn layer-corner-radius! [l r] (objc-msg-send-1dvoid l (sel "setCornerRadius:") (double r)))

;; NSAttributedString: tracking
(def NS-KERN-ATTR "NSKern")
(defn number-double [d] (objc-msg-send-1d (cls "NSNumber") (sel "numberWithDouble:") (double d)))

;; Auto Layout: a view's own width or height, two views the same height, and
;; the root's bottom edge
;; Each of these returns the constraint it activated, so the widget layer can
;; keep it and deactivate it when the prop that asked for it goes away — a
;; view glimmer reuses across renders must not keep yesterday's geometry.
(defn deactivate! [c] (objc-msg-send-1intvoid c (sel "setActive:") 0))
(defn superview [v] (let [s (objc-msg-send-0 v (sel "superview"))] (when-not (ffi/null? s) s)))
(defn- activated [c] (activate! c) c)
(defn equal-height!
  "`v`'s height equals `other`'s — the way two spacers share slack, since a
  stack stretches only one of them on its own."
  [v other]
  (activated (constraint v ATTR-HEIGHT other ATTR-HEIGHT 1.0 0.0)))
(defn size-constraint!
  "Pin `attr` (ATTR-WIDTH or ATTR-HEIGHT) of `v` to `n` points."
  [v attr n]
  (activated (constraint v attr ffi/null ATTR-NONE 1.0 n)))
(defn center-y!
  "`v`'s centre is `parent`'s, `offset` points down (negative: up)."
  [v parent offset]
  (activated (constraint v ATTR-CENTER-Y parent ATTR-CENTER-Y 1.0 offset)))
(defn pin-bottom-to-safe-area!
  "The fourth edge pin-to-safe-area! leaves free, for a root that should fill
  the screen rather than hug its content."
  [child parent]
  (activate! (constraint child ATTR-BOTTOM (safe-area-guide parent) ATTR-BOTTOM 1.0 0.0)))

;; NSMutableDictionary / NSArray, enough for a font descriptor
(defn dictionary-new [] (new-obj "NSMutableDictionary"))
(defn dictionary-set! [d k v] (objc-msg-send-2pvoid d (sel "setObject:forKey:") v k) d)
(defn array-with-object [o] (objc-msg-send-1p (cls "NSArray") (sel "arrayWithObject:") o))
(defn array-new [] (new-obj "NSMutableArray"))
(defn array-add! [a o] (objc-msg-send-1pvoid a (sel "addObject:") o) a)

(defn small-caps-font
  "`font` with the upper-case-to-small-caps feature (AAT type 38 / selector 1,
  OpenType c2sc). Size 0 keeps the descriptor's size. Subtle by design: the
  system font's small capitals stand about four-fifths of its cap height."
  [font]
  (let [setting (-> (dictionary-new)
                    (dictionary-set! (nsstring "CTFeatureTypeIdentifier")     (number-int FEATURE-UPPER-CASE))
                    (dictionary-set! (nsstring "CTFeatureSelectorIdentifier") (number-int SELECTOR-UPPER-SMALL-CAPS)))
        attrs   (-> (dictionary-new)
                    (dictionary-set! (nsstring "NSCTFontFeatureSettingsAttribute") (array-with-object setting)))
        desc    (objc-msg-send-1p (objc-msg-send-0 font (sel "fontDescriptor"))
                                  (sel "fontDescriptorByAddingAttributes:") attrs)]
    (objc-msg-send-1p1d (cls "UIFont") (sel "fontWithDescriptor:size:") desc 0.0)))

;; --- a photograph behind the type (2026-09-05) ------------------------------
(ffi/defcfn object-get-class "object_getClass" [:pointer] :pointer)   ; of a class: its metaclass

(def CONTENT-MODE-SCALE-ASPECT-FILL 2)

(defn view-new [] (new-obj "UIView"))
(defn set-clips! [v b] (objc-msg-send-1intvoid v (sel "setClipsToBounds:") (if b 1 0)))
(defn set-content-mode! [v m] (objc-msg-send-1i64void v (sel "setContentMode:") m))

;; UIImageView, from a file in the bundle
(defn bundle-path [] (nsstring->str (objc-msg-send-0 (objc-msg-send-0 (cls "NSBundle") (sel "mainBundle")) (sel "bundlePath"))))
(defn image-with-file [path] (objc-msg-send-1p (cls "UIImage") (sel "imageWithContentsOfFile:") (nsstring path)))
(defn image-view-new [] (new-obj "UIImageView"))
(defn image-view-image! [v img] (objc-msg-send-1pvoid v (sel "setImage:") img))

;; colours with alpha, and their CGColor for a layer
(defn color-hex-alpha
  "\"#rrggbb\" and an alpha 0–1 into a UIColor."
  [hex alpha]
  (let [h (subs hex 1)]
    (objc-msg-send-4d (cls "UIColor") (sel "colorWithRed:green:blue:alpha:")
                      (/ (hex->int (subs h 0 2)) 255.0)
                      (/ (hex->int (subs h 2 4)) 255.0)
                      (/ (hex->int (subs h 4 6)) 255.0)
                      (double alpha))))
(defn cg-color [uicolor] (objc-msg-send-0 uicolor (sel "CGColor")))

;; a border on a layer
(defn layer-border! [l width color]
  (objc-msg-send-1dvoid l (sel "setBorderWidth:") (double width))
  (objc-msg-send-1pvoid l (sel "setBorderColor:") (cg-color color)))

;; a font by PostScript name
(defn font-named [name size] (objc-msg-send-1p1d (cls "UIFont") (sel "fontWithName:size:") (nsstring name) (double size)))

;; the gradient view: a UIView whose layer is a CAGradientLayer
(defonce ^:private layer-class-cb
  (delay (ffi/foreign-callable (fn [_self _cmd] (cls "CAGradientLayer"))
                               [:pointer :pointer] :pointer :collect-safe)))
(defonce ^:private gradient-class
  (delay
    (let [existing (objc-get-class "GlimmerGradientView")]
      (if (and existing (not (ffi/null? existing)))
        existing
        (let [c (objc-allocate-class-pair (cls "UIView") "GlimmerGradientView" 0)]
          (class-add-method (object-get-class c) (sel "layerClass") @layer-class-cb "#@:")
          (objc-register-class-pair c)
          c)))))
(defn gradient-view-new []
  (objc-msg-send-0 (objc-msg-send-0 @gradient-class (sel "alloc")) (sel "init")))
(defn gradient-stops!
  "`stops`: [[uicolor location] ...] top to bottom, onto the view's own layer."
  [v stops]
  (let [l      (layer v)
        colors (reduce (fn [a [c _]] (array-add! a (cg-color c))) (array-new) stops)
        locs   (reduce (fn [a [_ loc]] (array-add! a (number-double loc))) (array-new) stops)]
    (objc-msg-send-1pvoid l (sel "setColors:") colors)
    (objc-msg-send-1pvoid l (sel "setLocations:") locs)))

;; two more pins
(defn pin-to-edges!
  "All four edges of `child` to `parent`'s — full bleed, under the status bar."
  [child parent]
  (set-translates-autoresizing! child false)
  (doseq [a [ATTR-TOP ATTR-LEADING ATTR-TRAILING ATTR-BOTTOM]]
    (activate! (constraint child a parent a 1.0 0.0))))
(defn pin-to-safe-area-all!
  "All four edges of `child` to `parent`'s safe-area guide."
  [child parent]
  (set-translates-autoresizing! child false)
  (let [guide (safe-area-guide parent)]
    (doseq [a [ATTR-TOP ATTR-LEADING ATTR-TRAILING ATTR-BOTTOM]]
      (activate! (constraint child a guide a 1.0 0.0)))))

;; --- a date, the way the phone writes one (1.1) ------------------------------
(def DATE-STYLE-MEDIUM 2)   ; NSDateFormatterMediumStyle: "Sep 5, 2026"
(def DATE-STYLE-NONE   0)

(defonce ^:private date-formatter
  (delay
    (let [f (new-obj "NSDateFormatter")]
      (objc-msg-send-1i64void f (sel "setDateStyle:") DATE-STYLE-MEDIUM)
      (objc-msg-send-1i64void f (sel "setTimeStyle:") DATE-STYLE-NONE)
      f)))

(defn format-date
  "`millis` since the epoch as the phone's medium date, in its locale."
  [millis]
  (let [d (objc-msg-send-1d (cls "NSDate") (sel "dateWithTimeIntervalSince1970:")
                            (/ (double millis) 1000.0))]
    (nsstring->str (objc-msg-send-1p @date-formatter (sel "stringFromDate:") d))))

;; --- UIScrollView (1.1) -------------------------------------------------------
(defn scroll-view-new [] (new-obj "UIScrollView"))
(defn content-guide [s] (objc-msg-send-0 s (sel "contentLayoutGuide")))
(defn frame-guide   [s] (objc-msg-send-0 s (sel "frameLayoutGuide")))
(defn pin-to-guide!
  "All four edges of `child` to `guide`'s."
  [child guide]
  (set-translates-autoresizing! child false)
  (doseq [a [ATTR-TOP ATTR-LEADING ATTR-TRAILING ATTR-BOTTOM]]
    (activate! (constraint child a guide a 1.0 0.0))))
(defn equal-width!
  "`child`'s width equals `guide`'s."
  [child guide]
  (activate! (constraint child ATTR-WIDTH guide ATTR-WIDTH 1.0 0.0)))

;; --- a framework, a block, a timer, a value (1.2) ---------------------------
(defonce ^:private frameworks-loaded (atom #{}))

(defn load-framework!
  "dlopen /System/Library/Frameworks/<name>.framework/<name>. Idempotent;
  meaningful only in the simulator or on a phone."
  [name]
  (when-not (@frameworks-loaded name)
    (ffi/load-library (str "/System/Library/Frameworks/" name ".framework/" name))
    (swap! frameworks-loaded conj name))
  nil)

(defn data-symbol
  "The address of a data symbol — an extern NSString constant, say — from
  `framework`, or nil when it is not there, which on an older iOS a newer
  constant is not; ffi/read it as :pointer for the object. dlopen of an
  already-loaded framework answers the same handle."
  [framework name]
  (let [h (c-dlopen (str "/System/Library/Frameworks/" framework ".framework/" framework) RTLD-NOW)
        p (if (ffi/null? h) ffi/null (c-dlsym h name))]
    (when-not (ffi/null? p) p)))

(def BLOCK-IS-GLOBAL 268435456)   ; 1 << 28

(defonce ^:private global-block-isa
  (delay (c-dlsym (c-dlopen "/usr/lib/libSystem.B.dylib" RTLD-NOW) "_NSConcreteGlobalBlock")))

(defonce ^:private block-descriptor
  (delay (doto (ffi/alloc 16)
           (ffi/write :uint64 0 0)
           (ffi/write :uint64 32 8))))   ; reserved, size — value then offset (0.8.0's order)

(defn make-block
  "A global block whose invoke is `callable`, a foreign-callable taking the
  block pointer first and then the block's arguments. Never freed: 32 bytes,
  on purpose, since the runtime reads its flags after invoke returns."
  [callable]
  (doto (ffi/alloc 32)
    (ffi/write :pointer @global-block-isa 0)
    (ffi/write :int32   BLOCK-IS-GLOBAL   8)
    (ffi/write :int32   0                 12)
    (ffi/write :pointer callable          16)
    (ffi/write :pointer @block-descriptor 24)))

(defn nsvalue->doubles
  "The `n` doubles inside NSValue `v` — a CLLocationCoordinate2D is two —
  read through getValue:size:, since the FFI takes no struct back by value."
  [v n]
  (let [buf (ffi/alloc (* 8 n))]
    (try
      (objc-msg-send-1p1i64void v (sel "getValue:size:") buf (* 8 n))
      (mapv #(ffi/read buf :double (* 8 %)) (range n))
      (finally (ffi/free buf)))))

(defn value-for-key
  "KVC: `obj`'s value for `key`, an object — the way a struct property is
  asked for as an NSValue."
  [obj key]
  (objc-msg-send-1p obj (sel "valueForKey:") (nsstring key)))

(defn timer!
  "An NSTimer on the current run loop — the main one when sent from it, which
  every caller so far is — `seconds` from now, sending `selector` to `target`
  once. Returns the timer, for invalidate."
  [seconds target selector]
  (objc-msg-send-1d3p1int (cls "NSTimer")
                          (sel "scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:")
                          (double seconds) target (sel selector) ffi/null 0))

(defn invalidate! [timer] (objc-msg-send-0void timer (sel "invalidate")))

(ffi/defcfn objc-msg-send-3pvoid "objc_msgSend" [:pointer :pointer :pointer :pointer :pointer] :void)   ; openURL:options:completionHandler: (#16)

(defn open-url!
  "Hand a URL to the system: the default browser for https, the default
  mail app for mailto. Nothing comes back; a phone with no app for the
  scheme does nothing."
  [s]
  (let [url (objc-msg-send-1p (cls "NSURL") (sel "URLWithString:") (nsstring s))
        app (objc-msg-send-0 (cls "UIApplication") (sel "sharedApplication"))]
    (when-not (ffi/null? url)
      (objc-msg-send-3pvoid app (sel "openURL:options:completionHandler:") url ffi/null ffi/null))))

(defn bundle-version
  "The version and build the store stamped on the bundle, as \"1.2.0 (103)\";
  nil when either key is missing, which is the host."
  []
  (let [info        (objc-msg-send-0 (objc-msg-send-0 (cls "NSBundle") (sel "mainBundle")) (sel "infoDictionary"))
        info-string (fn [k] (nsstring->str (objc-msg-send-1p info (sel "objectForKey:") (nsstring k))))
        v           (info-string "CFBundleShortVersionString")
        b           (info-string "CFBundleVersion")]
    (when (and v b) (str v " (" b ")"))))
