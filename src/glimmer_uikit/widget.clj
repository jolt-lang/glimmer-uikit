(ns glimmer-uikit.widget
  "Hiccup -> UIKit views. A data-driven registry maps hiccup tags to view
  constructors, prop maps to UIKit setters, and :on-click (or :on-toggled) to a handler wired
  through the shared action target (a dynamic ObjC class whose method IMP is a
  jolt foreign-callable). This layer creates/patches views and manages container
  children; glimmer's reconciler decides when, reaching these functions through
  the backend map in glimmer-uikit.core.

  Tag mapping:
    :window           the root view controller's view (container only; never created here)
    :box/:hbox/:vbox  UIStackView (axis = UILayoutConstraintAxis)
    :button           UIButton (system)
    :checkbutton      UIButton (system): the tile is the box, an SF Symbol checkmark when active — UIKit has no checkbox of its own
    :label            UILabel
    :layers           UIView; children fill it, back to front (the first behind, the last in front)
    :image            UIImageView, aspect fill, from a file in the bundle (:src)
    :gradient         a UIView whose own layer is a CAGradientLayer (:stops)
    :scroll           a UIScrollView over one vertical stack; children scroll vertically, never sideways

  Events are connected once at mount. UIKit does not fire actions for programmatic
  setTitle:/setText:, so no re-render feedback suppression is needed.

  Layout notes: box :margin maps to layoutMargins (with
  isLayoutMarginsRelativeArrangement); per-child :halign/:valign drive the PARENT
  stack's alignment (last child with an alignment wins); :hexpand/:vexpand lower
  the child's content-hugging priority along the axis. :background paints any
  view; :width/:height pin its size; :vfill on the root box pins its bottom to
  the safe area so a :vexpand child can push a footer down; :height-anchor g
  and :height-like g make two views the same height, which is how two spacers
  share the slack; :center-y (true, or an offset in points) puts a view at
  its parent stack's vertical centre, the spacers around it absorbing the
  difference. Constraints and hugging follow the props: what a prop asked
  for is dropped when the prop is absent on a later render. :alpha on any
  view; :border [width hex alpha] on a button; :full-bleed on the root fills
  the window under the status bar; :safe on a :layers child fills its safe
  area instead of its edges.

  Events: the target/action is added once at mount, and the handler behind
  :on-click is replaced on every render — glimmer reuses a view whose tag
  matches at the same position across renders, even across screens."
  (:require [clojure.string :as str]
            [glimmer-uikit.ffi :as u]
            [jolt.ffi :as ffi]))

;; --- value marshalling -------------------------------------------------------
(defn escape-markup
  "Escape `&`, `<`, `>`, `\"` and `'` so `s` can sit inside a Pango markup
  string — as text, or as an attribute value between double quotes. The
  five hiccup escapes, in hiccup's order: `&` first, so the entities
  themselves are not re-encoded. `'` is `&apos;`, XML's name, which
  `decode-entities` reads back."
  ^String [^String s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

;; --- Pango markup from hiccup data ------------------------------------------
;; Pango's text-attribute markup is a small XML subset (b, i, span, a, ...), NOT
;; HTML. Hiccup serializes vectors to a string and escapes content/attrs, but it
;; is HTML-flavoured — it will happily emit <div>, <br>, or a typo'd span
;; attribute. So we validate the hiccup *data* against Pango's vocabulary and
;; then walk it to a string ourselves (markup-render): a bad fragment fails
;; loudly at the call site instead of rendering silently wrong, and nothing
;; HTML-flavoured can leak in, because nothing reaches the walk that the table
;; has not admitted.
(def ^:private pango-tags
  "Pango markup vocabulary: tag -> the set of attributes it accepts, or nil when
  the tag takes no attributes."
  {:span #{:font_desc :font_family :face :size :style :weight :variant :stretch
           :foreground :color :background :alpha :underline :underline_color :rise
           :strikethrough :strikethrough_color :fallback :lang :letter_spacing
           :show :line_height :allow_breaks :insert_hyphens :text_transform
           :gravity :gravity_hint :overline :overline_color}
   :a    #{:href}
   :b nil :big nil :i nil :mark nil :s nil :small nil :sub nil :sup nil :tt nil
   :u nil})

(defn- markup-element? [form] (and (vector? form) (keyword? (first form))))

(declare markup-validate!)

(defn- markup-validate-element! [form]
  (let [tag     (first form)
        body    (rest form)
        attrs?  (map? (first body))
        attrs   (if attrs? (first body) nil)
        children (if attrs? (rest body) body)]
    (when-not (contains? pango-tags tag)
      (throw (ex-info (str "glimmer/markup: :" (name tag) " is not a Pango tag")
                      {:tag tag})))
    (let [allowed (get pango-tags tag)]
      (when (and attrs (seq attrs))
        (if (nil? allowed)
          (throw (ex-info (str "glimmer/markup: :" (name tag) " takes no attributes")
                          {:tag tag :attrs (keys attrs)}))
          (doseq [k (keys attrs)]
            (when-not (contains? allowed k)
              (throw (ex-info (str "glimmer/markup: :" (name k)
                                   " is not a :" (name tag) " attribute")
                              {:tag tag :attr k}))))))
      (run! markup-validate! children))))

(defn- markup-validate! [form]
  (cond
    (markup-element? form)  (markup-validate-element! form)
    (sequential? form)      (run! markup-validate! form)
    :else                   nil))

(defn- markup-render
  "The walk hiccup did, for Pango's subset: an element is its tag, its
  attributes in name order, its children and a close tag; a string is
  escaped; a seq is its items in turn; nil is nothing; anything else is
  its str, escaped. An attribute that is true renders as name=\"name\";
  nil or false drops it — hiccup's rules, so the bytes match."
  [form]
  (cond
    (markup-element? form)
    (let [[tag & body] form
          attrs?   (map? (first body))
          attrs    (when attrs? (first body))
          children (if attrs? (rest body) body)
          t        (name tag)]
      (str "<" t
           (apply str (for [[k v] (sort-by #(name (key %)) attrs)
                            :when (and (some? v) (not (false? v)))]
                        (str " " (name k) "=\""
                             (if (true? v) (name k) (escape-markup (str v)))
                             "\"")))
           ">"
           (apply str (map markup-render children))
           "</" t ">"))
    (sequential? form) (apply str (map markup-render form))
    (nil? form)        ""
    :else              (escape-markup (str form))))

(defn markup
  "Render hiccup `form` to a Pango markup string for a label's :markup prop.
  The data is first validated against Pango's tag/attribute vocabulary, so an
  HTML-only tag (:div, :br) or a typo'd attribute (:forground) throws here
  rather than producing markup a label can't render."
  [form]
  (markup-validate! form)
  (markup-render form))

(defn markup-string
  "Coerce a label's :markup prop to a Pango markup string. A string passes
  through as-is (already markup); anything else is treated as hiccup and
  rendered via `markup`."
  [m]
  (if (string? m) m (markup m)))

;; --- Pango markup -> NSAttributedString -------------------------------------
;; UIKit has no Pango. :markup renders through a small subset mapped onto
;; NSAttributedString attributes: <b> <i> <s> <u> and span{foreground|color,
;; size (named, or an integer in 1/1024ths of a point), weight=bold,
;; strikethrough, underline}. Unknown-but-valid tags/attrs are parsed and
;; dropped — their text survives.
(def ^:private named-sizes
  {"xx-small" 11 "x-small" 13 "small" 15 "medium" 17
   "large" 20 "x-large" 24 "xx-large" 34})

(defn- parse-attrs
  "Extract k='v' pairs from a tag string."
  [tag]
  (into {}
        (for [[_ k v] (re-seq #"([a-zA-Z_]+)=['\"]([^'\"]*)['\"]" tag)]
          [(keyword k) v])))

(defn- tag-name [tag] (str/replace (str/replace tag #"[<>/]" "") #"\s.*$" ""))

(defn span-style
  "The style map a <span>'s attributes imply. Public so the mapping is
  testable without UIKit; :variant \"smallcaps\", :letter_spacing (Pango
  units, 1/1024 pt, signed, to :kern in points) and :font_family (a
  PostScript name) are the 2026-09-05 additions."
  [a]
  (cond-> {}
    (or (:foreground a) (:color a))
    (assoc :color (or (:foreground a) (:color a)))
    (:size a) (assoc :size (:size a))
    (= "bold" (:weight a)) (assoc :weight true)
    (= "true" (:strikethrough a)) (assoc :strike true)
    (= "true" (:underline a)) (assoc :underline true)
    (= "smallcaps" (:variant a)) (assoc :smallcaps true)
    (:font_family a) (assoc :family (:font_family a))
    (and (:letter_spacing a) (re-matches #"-?\d+(\.\d+)?" (:letter_spacing a)))
    (assoc :kern (/ (double (read-string (:letter_spacing a))) 1024.0))))

(defn- open-tag [stack tag]
  (case (tag-name tag)
    "b"    (conj stack {:bold true})
    "i"    (conj stack {:italic true})
    "s"    (conj stack {:strike true})
    "u"    (conj stack {:underline true})
    "span" (conj stack (span-style (parse-attrs tag)))
    stack))

(defn- markup->segments
  "Parse a Pango markup string into [[text style-map] ...] segments."
  [^String s]
  (first
    (reduce
      (fn [[segs stack] [_full tag txt]]
        (cond
          tag (if (str/starts-with? tag "</")
                [segs (pop stack)]
                [segs (open-tag stack tag)])
          :else [ (conj segs [txt (apply merge stack)]) stack]))
      [[] []]
      (re-seq #"(</?[a-zA-Z]+(?:\s+[^<>]*)?>)|([^<>]+)" s))))

(defn- decode-entities [s]
  (-> s
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&apos;" "'")
      (str/replace "&amp;" "&")))

(defn- pango-size->pt
  "Pango font sizes are a named size (\"large\") or an integer in 1/1024ths of
  a point (\"30000\" = 29.3pt). Map either to a point size."
  [s]
  (or (named-sizes s)
      (when (and (string? s) (re-matches #"\d+" s))
        (/ (reduce (fn [acc c] (+ (* acc 10) (- (int c) 48))) 0 s) 1024.0))))

(defn- style-font [style]
  (let [size   (double (or (pango-size->pt (:size style)) 17.0))   ; 17 = UIKit body
        weight (if (or (:weight style) (:bold style)) u/FONT-WEIGHT-BOLD u/FONT-WEIGHT-REGULAR)
        named  (when-let [f (:family style)] (u/font-named f size))   ; null for a name iOS lacks
        font   (cond
                 (and named (not (ffi/null? named))) named
                 (:italic style)                    (u/italic-font-size size)
                 :else                              (u/system-font size weight))]
    (if (:smallcaps style) (u/small-caps-font font) font)))

(defn- apply-style! [a style start len]
  (when (pos? len)
    (let [font (style-font style)]
      (when font (u/attributed-add! a u/NS-FONT-ATTR font start len)))
    (when (:strike style)
      (u/attributed-add! a u/NS-STRIKETHROUGH-ATTR (u/number-int 1) start len))
    (when (:underline style)
      (u/attributed-add! a u/NS-UNDERLINE-ATTR (u/number-int 1) start len))
    (when-let [c (:color style)]
      (u/attributed-add! a u/NS-FOREGROUND-COLOR-ATTR (u/color-hex c) start len))
    ;; tracking goes AFTER each glyph, so the last one is left alone or the
    ;; text sits off-centre by one gap
    (when-let [k (:kern style)]
      (when (> len 1)
        (u/attributed-add! a u/NS-KERN-ATTR (u/number-double k) start (dec len))))))

(defn markup->attributed
  "Render a Pango markup STRING to an NSAttributedString (UILabel content)."
  [^String s]
  (let [segs (map (fn [[t st]] [(decode-entities t) st]) (markup->segments s))
        a    (u/attributed-new (apply str (map first segs)))]
    (loop [segs (seq segs) pos 0]
      (when-let [[txt style] (first segs)]
        (let [len (count txt)]
          (apply-style! a style pos len)
          (recur (next segs) (+ pos len)))))
    a))

;; --- tag aliases (sugar) -----------------------------------------------------
;; :hbox / :vbox are both UIStackView; the difference is the axis.
(def ^:private aliases {:hbox :box :vbox :box})
(def ^:private tag-orientation {:hbox :horizontal :vbox :vertical})

(defn- normalize-tag [tag] (get aliases tag tag))

(defn with-orientation
  "Inject the orientation implied by an :hbox/:vbox tag into its props, unless
  the caller already set :orientation. A no-op for any other tag."
  [tag props]
  (if-let [o (tag-orientation tag)]
    (if (contains? props :orientation) props (assoc props :orientation o))
    props))

;; --- event registry ----------------------------------------------------------
;; The :on-* keys glimmer knows. :on-click and a checkbutton's :on-toggled are
;; wired (#3); the atom lets apply-props! skip event keys.
(def signals (atom {:on-click true :on-toggled true}))

(defn register-signal!
  "Register an :on-* event key so apply-props! skips it."
  [event _signal]
  (swap! signals assoc event true)
  nil)

;; --- what this layer remembers about a view ---------------------------------
;; One map per view address. The keys:
;;   :handler      the zero-arg :on-click or :on-toggled handler
;;   :alignment    [halign valign], for the parent stack
;;   :expanded     the axes whose hugging this layer lowered
;;   :constraints  kind -> {:value v :c NSLayoutConstraint}
;;   :fill?        a root that pins its bottom too (:vfill)
;;   :bleed?       a root pinned to the window's edges, not its safe area (:full-bleed)
;;   :safe?        a :layers child pinned to its safe area, not its edges (:safe)
;;   :like         the :height-anchor view to match, once both are in a stack
;;   :center       the :center-y offset, until the view has a parent
(defonce ^:private views (atom {}))

(defn- remember! [view k v] (swap! views assoc-in [view k] v))
(defn- recall [view k] (get-in @views [view k]))
(defn- drop! [view k]
  (swap! views (fn [m] (if (contains? m view) (update m view dissoc k) m))))

(defn- forget!
  "Drop everything this layer remembers about `widget`'s address. A fresh view
  can be allocated where a dead one was — the summary's stat rows are freed
  when the splash comes, and the splash's new buttons land on their bytes —
  and this layer's memory is keyed by address. The constraints were the
  first to bite: they said the new button's height was already pinned, so it
  was not, and the button came up at its natural size. Nothing is
  deactivated: the constraints belonged to views that no longer exist."
  [widget]
  (swap! views dissoc widget))

;; --- the shared action target ------------------------------------------------
;; One dynamic ObjC class ("GlimmerTarget") carries every :on-click and :on-toggled. Its fire:
;; IMP is a jolt foreign-callable that dispatches on the sender pointer, so a
;; single instance is every button's target. Built lazily: this namespace loads
;; on the host during jolt build and on Linux CI, where objc_getClass does not
;; exist; the first :on-click forces it, on the main thread, inside the app.

(defonce ^:private fire-cb
  (delay
    (ffi/foreign-callable
      (fn [_self _cmd sender]
        (when-let [h (recall sender :handler)] (h))
        0)
      [:pointer :pointer :pointer] :void :collect-safe)))

(defonce ^:private target
  (delay
    (u/objc-msg-send-0 (u/ensure-class! "GlimmerTarget" "NSObject"
                                        #(u/class-add-method % (u/sel "fire:") @fire-cb "v@:@"))
                       (u/sel "new"))))

(defn invoker
  "The shared GlimmerTarget instance (created on first use)."
  [] @target)

(defn connect-signals!
  "Wire :on-click, or a checkbutton's :on-toggled, on `widget`. The
  target/action is added once at mount; the handler itself lives in `views`
  and is replaced on every render — see update-handler!."
  [widget props]
  (when-let [h (or (:on-click props) (:on-toggled props))]
    (remember! widget :handler h)
    (u/add-target! widget (invoker) (u/sel "fire:") u/EVENT-TOUCH-UP-INSIDE)))

(defn update-handler!
  "The re-render half of connect-signals!: a widget glimmer reuses at the same
  position keeps its UIKit target but must take the NEW handler, or a button
  that used to say Start 18 Holes and now says End Round starts a round.
  (2026-09-05, found on the phone the day the splash and hole screens came to
  end in two buttons each.) The same for :on-toggled (#3)."
  [widget props]
  (when-let [h (or (:on-click props) (:on-toggled props))]
    (remember! widget :handler h)))

(defn handler-for
  "The zero-arg handler a widget would fire, or nil. For tests."
  [widget]
  (recall widget :handler))

;; --- widget specs ------------------------------------------------------------
;; Each spec: {:ctor (fn [props] view) :apply (fn [view props]) :container kw
;;             :connect (fn [view props])?}
(defn- window-spec []
  {:ctor  (fn [_] (throw (ex-info "glimmer-uikit: :window is the root container; hiccup cannot create one" {})))
   :apply (fn [_ _] nil)
   :container :window})

(defn- box-margins
  "The stack's layout margins implied by a box's margin props, or nil."
  [p]
  (let [m (:margin p)
        top (or (:margin-top p) m) bottom (or (:margin-bottom p) m)
        left (or (:margin-left p) (:margin-start p) m)
        right (or (:margin-right p) (:margin-end p) m)]
    (when (or top left bottom right) [top left bottom right])))

(defn- box-spec []
  {:ctor  (fn [_]
            (let [s (u/stack-new)]
              ;; UIStackView defaults to fill on the cross axis; NSStackView centers.
              (u/stack-alignment! s u/ALIGN-CENTER)
              s))
   :apply (fn [w p]
            (when (contains? p :spacing)     (u/stack-spacing! w (:spacing p)))
            (when (contains? p :homogeneous)
              (u/stack-distribution! w (if (:homogeneous p)
                                         u/DISTRIBUTION-FILL-EQUALLY
                                         u/DISTRIBUTION-FILL)))
            (when (contains? p :orientation)
              (u/stack-axis! w (if (= :vertical (:orientation p))
                                 u/AXIS-VERTICAL
                                 u/AXIS-HORIZONTAL)))
            (when-let [[t l b r] (box-margins p)]
              (u/stack-layout-margins! w t l b r)))
   :container :box})

(defn button-font-args
  "[size weight] for a button's title font from :font-size and :font-weight
  (:bold or anything else), or nil when there is no :font-size. Public so the
  mapping is testable without UIKit."
  [p]
  (when-let [size (:font-size p)]
    [(double size) (if (= :bold (:font-weight p)) u/FONT-WEIGHT-BOLD u/FONT-WEIGHT-REGULAR)]))

(defn xalign->side
  "The side an :xalign from 0 to 1 puts content on: :left, :center or :right.
  Public so the thresholds are testable without UIKit."
  [x]
  (cond (<= x 0.34) :left
        (>= x 0.66) :right
        :else       :center))

(defn- ->button-align [x]
  (case (xalign->side x)
    :left   u/BUTTON-ALIGN-LEFT
    :center u/BUTTON-ALIGN-CENTER
    :right  u/BUTTON-ALIGN-RIGHT))

(defn- button-spec []
  {:ctor  (fn [p] (doto (u/button-new (or (:label p) ""))
                    ;; a title too long for the tile loses its tail, not its middle (1.2)
                    (-> u/button-title-label (u/label-line-break! u/LINE-BREAK-TAIL))))
   :apply (fn [w p]
            (when (contains? p :label)      (u/button-title! w (:label p)))
            ;; 1.1: a title that begins with a date the backend formats, then :label
            (when-let [ms (:label-date p)] (u/button-title! w (str (u/format-date ms) (:label p))))
            (when (contains? p :sensitive)
              (u/control-enabled! w (:sensitive p))
              ;; a filled button shows no disabled state of its own — the title
              ;; colour set for normal is used for every state — so dim the whole
              ;; thing; a system button was dimming its blue title already
              (u/set-alpha! w (if (:sensitive p) 1.0 0.4)))
            ;; polish (2026-09-05): a filled, sized button
            (when (contains? p :foreground) (u/button-title-color! w (u/color-hex (:foreground p))))
            (when-let [[size weight] (button-font-args p)]
              (u/set-font! (u/button-title-label w) (u/system-font size weight)))
            (when (contains? p :radius)     (u/layer-corner-radius! (u/layer w) (:radius p)))
            (when-let [pad (:padding p)]    (u/button-content-insets! w 0 pad 0 pad))   ; #18
            (when (contains? p :xalign)                                                  ; #18
              (u/button-horizontal-alignment! w (->button-align (:xalign p))))
            (when-let [[width hex alpha] (:border p)] (u/layer-border! (u/layer w) width (u/color-hex-alpha hex alpha))))
   :container :none})

(defn- checkbutton-spec
  "A checkbox, which UIKit does not have: a system button with no title whose
  tile IS the box — empty when not :active, and an SF Symbol checkmark for
  its image when it is, which UIKit centres in the tile exactly, where a
  glyph in the title sat a little high and a little left (#3, the first
  user feedback). :symbol is the checkmark's point size; :foreground tints
  it. Everything else — background, radius, size, insets — is the button's."
  []
  (let [button (button-spec)]
    {:ctor  (fn [_] (u/button-new ""))
     :apply (fn [w p]
              ((:apply button) w (dissoc p :active :symbol :foreground))   ; the tint is ours, not a title colour
              (when (contains? p :active)
                (u/button-image! w (if (:active p)
                                     (u/system-image "checkmark" (or (:symbol p) 24))
                                     ffi/null)))
              (when (contains? p :foreground) (u/set-tint-color! w (u/color-hex (:foreground p)))))
     :container :none}))

(defn- ->text-align [x]
  (case (xalign->side x)
    :left   u/TEXT-ALIGN-LEFT
    :center u/TEXT-ALIGN-CENTER
    :right  u/TEXT-ALIGN-RIGHT))

(defn- ->line-break [e]
  (case e
    :start  u/LINE-BREAK-HEAD
    :middle u/LINE-BREAK-MIDDLE
    :end    u/LINE-BREAK-TAIL
    :none   nil))

(defn- label-spec []
  {:ctor  (fn [p] (u/label-new (or (:label p) (:text p) "")))
   :apply (fn [w p]
            (when (contains? p :label)  (u/label-text! w (:label p)))
            (when (contains? p :text)   (u/label-text! w (:text p)))
            (when (contains? p :markup) (u/label-attributed! w (markup->attributed (markup-string (:markup p)))))
            ;; 1.1: a date the backend formats — styled like :markup when :date-style gives span attributes
            (when-let [ms (:date p)]
              (let [s (u/format-date ms)]
                (if-let [st (:date-style p)]
                  (u/label-attributed! w (markup->attributed (markup [:span st s])))
                  (u/label-text! w s))))
            (when (contains? p :xalign) (u/label-align! w (->text-align (:xalign p))))
            (when (contains? p :wrap)
              (u/label-line-break! w u/LINE-BREAK-WRAP)
              (u/label-lines! w 0))
            (when (contains? p :lines)  (u/label-lines! w (:lines p)))
            (when-let [m (and (contains? p :ellipsize) (->line-break (:ellipsize p)))]
              (u/label-line-break! w m)))
   :container :none})

;; --- a photograph behind the type (2026-09-05) -------------------------------
(defn- image-spec []
  {:ctor  (fn [_] (let [v (u/image-view-new)]
                    (u/set-content-mode! v u/CONTENT-MODE-SCALE-ASPECT-FILL)
                    (u/set-clips! v true)
                    v))
   :apply (fn [w p]
            (when-let [src (:src p)]              ; a file in the bundle
              (u/image-view-image! w (u/image-with-file (str (u/bundle-path) "/" src)))))
   :container :none})

(defn- gradient-spec []
  {:ctor  (fn [_] (u/gradient-view-new))
   :apply (fn [w p]
            (when-let [stops (:stops p)]         ; [[hex alpha location] ...], top to bottom
              (u/gradient-stops! w (mapv (fn [[hex alpha loc]] [(u/color-hex-alpha hex alpha) loc]) stops))))
   :container :none})

(defn- layers-spec []
  {:ctor  (fn [_] (u/view-new))
   :apply (fn [_ _] nil)
   :container :layers})

;; --- a scroll view (1.1) ------------------------------------------------------
;; A UIScrollView whose one subview is a vertical stack the constructor makes:
;; the stack's edges pinned to the scroll view's contentLayoutGuide (that is
;; what makes the content size) and its width to the frameLayoutGuide's (that
;; is what stops it scrolling sideways). Hiccup children go into the stack; the
;; reconciler hands the child operations the scroll view's pointer, so a
;; registry maps it to its stack and each operation delegates to the :box arm
;; with the stack as parent — so maybe-align! sees a row's :halign :fill, or
;; the rows would sit centred and narrow.
;; Not in `views`: create! forgets the scroll view after its :ctor records the
;; stack here.
(def ^:private scroll-boxes (atom {}))   ; scroll view -> its content stack

(defn- scroll-spec []
  {:ctor  (fn [_]
            (let [s (u/scroll-view-new)
                  b (u/stack-new)]
              (forget! b)                        ; a fresh view at a freed address inherits nothing
              (u/stack-axis! b u/AXIS-VERTICAL)
              (u/stack-alignment! b u/ALIGN-CENTER)
              (u/add-subview! s b)
              (u/pin-to-guide! b (u/content-guide s))
              (u/equal-width! b (u/frame-guide s))
              (swap! scroll-boxes assoc s b)
              s))
   :apply (fn [w p]
            (when-let [b (get @scroll-boxes w)]
              (when (contains? p :spacing) (u/stack-spacing! b (:spacing p)))))
   :container :scroll})

(defn- scroll-box
  "The content stack of scroll view `s`."
  [s]
  (get @scroll-boxes s))

(def specs
  (atom {:window   (window-spec)
         :box      (box-spec)
         :button   (button-spec)
         :checkbutton (checkbutton-spec)   ; #3
         :label    (label-spec)
         :image    (image-spec)
         :gradient (gradient-spec)
         :layers   (layers-spec)
         :scroll   (scroll-spec)}))

(defn register-widget!
  "Add a widget spec {:ctor :apply :container :connect?} under `tag`. create!
  calls the optional :connect once, last, to wire the view's own events."
  [tag spec]
  (swap! specs assoc tag spec)
  nil)

(defn- spec-for [tag] (@specs (normalize-tag tag)))

(defn container-kind
  "How `tag` holds children: :box, :layers, :scroll, :window, or :none."
  [tag]
  (:container (spec-for tag) :none))

;; --- universal props (apply to every widget, every tag) ----------------------
(defn- ->stack-alignment [halign valign axis]
  (if (= axis u/AXIS-VERTICAL)
    (case halign
      :start u/ALIGN-LEADING
      :end   u/ALIGN-TRAILING
      :fill  u/ALIGN-FILL
      u/ALIGN-CENTER)
    (case valign
      :top    u/ALIGN-LEADING
      :bottom u/ALIGN-TRAILING
      :fill   u/ALIGN-FILL
      u/ALIGN-CENTER)))

(def ^:private anchors (atom {}))        ; :height-anchor group -> view, for :height-like

;; --- constraints that follow props ------------------------------------------
;; glimmer reuses a view whose tag matches at the same position across
;; renders — across screens too — so a constraint a prop asked for must go
;; when the prop goes, or the hole screen's rows box comes back as the splash's
;; title box still pinned to the centre (or the other way round, which is how
;; this was found). One constraint per [view kind]; replaced when its value
;; changes, dropped when the prop is absent.
(defn- constrain!
  "Keep exactly one constraint of `kind` on `widget`: `wanted` is the prop's
  value or nil, `make` builds and activates the constraint for it."
  [widget kind wanted make]
  (let [{:keys [value c]} (get (recall widget :constraints) kind)]
    (when (and c (not= value wanted))
      (u/deactivate! c)
      (swap! views update-in [widget :constraints] dissoc kind))
    (when (and (some? wanted) (or (nil? c) (not= value wanted)))
      (swap! views assoc-in [widget :constraints kind] {:value wanted :c (make)}))))

(defn- center-y-offset [v] (cond (number? v) (double v) v 0.0 :else nil))

(defn- center-y!
  "Pin (or unpin) `widget`'s centre to its parent's, once it has one."
  [widget offset]
  (when-let [parent (u/superview widget)]
    (constrain! widget :center-y offset #(u/center-y! widget parent offset))))

(defn- hug!
  "Lower the hugging priority along `axis` for an expand prop, and put it back
  to UIKit's default (250) when a view this layer lowered no longer asks."
  [widget axis expand?]
  (cond
    expand?
    (do (u/set-hugging! widget u/PRIORITY-VERY-LOW axis)
        (swap! views update-in [widget :expanded] (fnil conj #{}) axis))
    (contains? (recall widget :expanded) axis)
    (do (u/set-hugging! widget u/PRIORITY-LOW axis)
        (swap! views update-in [widget :expanded] disj axis))))

(defn apply-widget-props!
  [widget props]
  (hug! widget u/AXIS-HORIZONTAL (:hexpand props))
  (hug! widget u/AXIS-VERTICAL   (:vexpand props))
  (when (or (contains? props :halign) (contains? props :valign))
    (remember! widget :alignment [(:halign props) (:valign props)]))
  ;; polish (2026-09-05): any view can be painted and sized; a root can fill
  (when (contains? props :background) (u/set-background! widget (u/color-hex (:background props))))
  (constrain! widget :width  (:width props)  #(u/size-constraint! widget u/ATTR-WIDTH  (:width props)))
  (constrain! widget :height (:height props) #(u/size-constraint! widget u/ATTR-HEIGHT (:height props)))
  (when (:vfill props)                (remember! widget :fill? true))
  ;; a photograph behind the type (2026-09-05)
  (when (contains? props :alpha)      (u/set-alpha! widget (:alpha props)))
  (when (:full-bleed props)           (remember! widget :bleed? true))
  (when (:safe props)                 (remember! widget :safe? true))
  ;; two views the same height: the anchor is registered under a group name
  ;; (always overwriting, so a stale one from an unmounted screen cannot be
  ;; paired with), and a :height-like view later in the same render remembers
  ;; it. The constraint itself waits for append-child!: a constraint between
  ;; two views needs a common ancestor, and at create! neither is in a stack
  ;; yet — the first build crashed on exactly that. Views are created and
  ;; appended in hiccup order, so the anchor is in the stack first.
  (when-let [g (:height-anchor props)] (swap! anchors assoc g widget))
  (when-let [g (:height-like props)]
    (when-let [other (get @anchors g)]
      (when-not (= other widget) (remember! widget :like other))))
  ;; :center-y (true, or an offset in points): the view's centre is its parent
  ;; stack's centre, the spacers around it taking whatever heights make that
  ;; true. Needs a parent: at create! there is none yet and append-child!
  ;; finishes the job; on a re-render there is, and the constraint is added,
  ;; replaced or dropped right here.
  (let [offset (center-y-offset (:center-y props))
        had?   (contains? (recall widget :constraints) :center-y)]
    (when (or offset had?)                 ; only then touch the view: the host tests use fake pointers
      (if (u/superview widget)
        (center-y! widget offset)
        (when offset (remember! widget :center offset))))))

;; --- public create / patch ---------------------------------------------------
(defn create!
  "Construct a fresh UIKit view for `tag`, apply `props`, wire :on-click or
  :on-toggled, then call the spec's :connect, if any. Returns the view
  pointer. Children are NOT added here — the reconciler appends them so it
  can reuse existing children across renders."
  [tag props]
  (let [props (with-orientation tag props)
        s (spec-for tag)
        widget ((:ctor s) props)]
    (forget! widget)
    ((:apply s) widget props)
    (apply-widget-props! widget props)
    (connect-signals! widget props)
    (when-let [connect (:connect s)] (connect widget props))
    widget))

(defn apply-props!
  "Re-apply the prop map to an existing view (re-render path). :on-* keys are
  not re-wired (the target stays from mount) but the handler behind :on-click
  or :on-toggled is replaced; keys whose value is nil are skipped."
  [tag widget props]
  (let [applied (into {} (filter (fn [[k v]] (and (not (contains? @signals k)) (some? v)))
                                 (with-orientation tag props)))]
    ((:apply (spec-for tag)) widget applied)
    (apply-widget-props! widget applied)
    (update-handler! widget props)))

(defn show!
  "Views are visible by default; :visible false hides instead."
  [widget props]
  (u/set-hidden! widget (false? (:visible props))))

;; --- container child management ----------------------------------------------
(defn- maybe-align!
  "Derive the parent stack's alignment from a child's :halign/:valign."
  [parent child]
  (when-let [[halign valign] (recall child :alignment)]
    (u/stack-alignment! parent (->stack-alignment halign valign (u/stack-axis parent)))))

(defn append-child!
  "Add `child` to the end of `parent`. Dispatches on the parent's container kind."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box    (do (u/stack-add-arranged! parent child)
                (maybe-align! parent child)
                (when-let [other (recall child :like)]
                  (u/equal-height! child other)
                  (drop! child :like))
                (when-let [offset (recall child :center)]
                  (center-y! child offset)
                  (drop! child :center)))
    :layers (do (u/add-subview! parent child)          ; back to front, in hiccup order
                (if (recall child :safe?)
                  (u/pin-to-safe-area-all! child parent)
                  (u/pin-to-edges! child parent))
                (when-let [offset (recall child :center)]
                  (center-y! child offset)
                  (drop! child :center)))
    :scroll (append-child! :box (scroll-box parent) child)
    :window (do (u/add-subview! parent child)
                (if (recall child :bleed?)
                  (u/pin-to-edges! child parent)
                  (do (u/pin-to-safe-area! child parent)
                      (when (recall child :fill?) (u/pin-bottom-to-safe-area! child parent)))))
    nil))

(defn remove-child!
  "Remove `child` from `parent`."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box    (do (u/stack-remove-arranged! parent child)
                (u/remove-from-superview! child))
    :layers (u/remove-from-superview! child)
    :scroll (remove-child! :box (scroll-box parent) child)
    :window (u/remove-from-superview! child)
    nil))

(defn replace-child!
  "Replace `old-child` with `new-child` at the same position in `parent`."
  [parent-tag parent old-child new-child]
  (case (container-kind parent-tag)
    :box    (let [i (u/stack-index-of! parent old-child)]
              (remove-child! parent-tag parent old-child)
              (u/stack-insert-arranged! parent new-child (max i 0))
              (maybe-align! parent new-child))
    ;; a :layers child replaced lands in front: remove then append. The splash's
    ;; layers never change tag at a position, so nothing here reorders.
    :layers (do (remove-child! parent-tag parent old-child)
                (append-child! parent-tag parent new-child))
    :scroll (replace-child! :box (scroll-box parent) old-child new-child)
    :window (do (remove-child! parent-tag parent old-child)
                (append-child! parent-tag parent new-child))
    nil))

(defn reorder-child!
  "Move `child` to sit immediately after `sibling` (nil = first position)
  within `parent`. A stack supports positional reordering; a scroll view
  delegates to its stack."
  [parent-tag parent child sibling]
  (case (container-kind parent-tag)
    :scroll (reorder-child! :box (scroll-box parent) child sibling)
    :box    (do (u/stack-remove-arranged! parent child)
                (if (nil? sibling)
                  (u/stack-insert-arranged! parent child 0)
                  (let [i (u/stack-index-of! parent sibling)]
                    (u/stack-insert-arranged! parent child (inc i)))))
    nil))

;; --- reading the live tree (for smoke examples) -----------------------------
(defn stack-children
  "The arranged subviews of a stack, in visual order."
  [stack]
  (let [arr (u/stack-arranged! stack)
        n   (u/array-count arr)]
    (mapv (fn [i] (u/array-get arr i)) (range n))))
