(ns glimmer-uikit.core
  "The UIKit backend for glimmer. Requiring this namespace installs it, after
  which glimmer's portable reconciler (glimmer.core) renders hiccup into real
  UIKit views:

    (ns myapp
      (:require [glimmer.ratom :as r]
                [glimmer.core :as ui]
                [glimmer-uikit.core]))            ; installs the UIKit backend

    (defn -main [& _] (ui/run my-app :title \"hello\"))

  The app loop is UIApplicationMain: run* registers a delegate class at runtime,
  hands its NAME to UIApplicationMain (which instantiates it and never returns),
  and the delegate's application:didFinishLaunchingWithOptions: builds the
  window and mounts the root component into its root view controller's view.
  Every event handler is a :collect-safe foreign-callable invoked from inside
  that loop. Off-thread re-renders land via a CFRunLoopSource on the main run
  loop."
  (:require [glimmer.backend :as b]
            [glimmer-uikit.ffi :as u]
            [glimmer-uikit.widget :as w]
            [jolt.ffi :as ffi]))

;; --- failures ------------------------------------------------------------------
;; Work that UIKit calls back into must not throw back across the FFI.
(defn- logged
  "Call `f` and return its value. When `f` throws, print the failure with
  `label` and return nil."
  [label f]
  (try (f)
       (catch :default e
         (println (str "glimmer-uikit: " label " failed:") e))))

;; --- marshalling work onto the main loop -------------------------------------
;; While UIApplicationMain owns the main thread, a ratom mutation made off it
;; (a future, a worker) would otherwise reconcile — calling UIKit — off the main
;; thread, which UIKit rejects. glimmer.core defers those re-renders through
;; backend/schedule, which lands here. A single CFRunLoopSource on the main run
;; loop drains a queue of thunks; posting is signal+wakeup.
(defonce ^:private scheduler
  (delay
    (let [queue   (atom [])
          perform (ffi/foreign-callable
                    (fn [_info]
                      ;; CAS drain: a concurrent (swap! queue conj work) either
                      ;; lands in `jobs` or survives for the next signal.
                      (let [[jobs _] (swap-vals! queue (constantly []))]
                        (run! #(logged "scheduled work" %) jobs)))
                    [:pointer] :void :collect-safe)
          ;; CFRunLoopSourceContext on arm64 (all 8-byte fields), as a layout:
          ;; jolt 0.8.0 swapped ffi/write's value and offset, and write-field
          ;; reads the same on both (README.org, "jolt 0.8.0: the write order").
          ctx-l (ffi/layout [:struct [[:version :int64] [:info :pointer] [:retain :pointer]
                                      [:release :pointer] [:copy-description :pointer]
                                      [:equal :pointer] [:hash :pointer] [:schedule :pointer]
                                      [:cancel :pointer] [:perform :pointer]]])
          ctx   (ffi/alloc (ffi/layout-size ctx-l))]
      (doseq [f [:version :info :retain :release :copy-description :equal :hash :schedule :cancel]]
        (ffi/write-field ctx ctx-l f 0))
      (ffi/write-field ctx ctx-l :perform perform)
      (let [src (u/cf-run-loop-source-create ffi/null 0 ctx)
            rl  (u/cf-run-loop-get-main)]
        (u/cf-run-loop-add-source rl src (u/default-mode))
        {:queue queue :source src :run-loop rl}))))

(defn- post-to-gui
  "Schedule zero-arg `work` on the main loop."
  [work]
  (let [{:keys [queue source run-loop]} @scheduler]
    (swap! queue conj work)
    (u/cf-run-loop-source-signal source)
    (u/cf-run-loop-wake-up run-loop))
  nil)

(defn schedule!
  "Schedule zero-arg `f` on the main loop. glimmer's re-renders take this route
  when loop-running?; exposed so smokes can drive the loop."
  [f]
  (post-to-gui f)
  nil)

;; --- the mounted app ---------------------------------------------------------
(defonce ^:private current-mount (atom nil))
(defonce ^:private pending-mount (atom nil))   ; mount-root!, stashed for the delegate
(defonce ^:private window (atom nil))          ; retained: nothing else holds it
(defonce ^:private root-background (atom nil)) ; :background from run opts, for the delegate

(defn root-inst
  "The mounted root instance atom of the running app (nil before launch). Lets
  a smoke reach the live tree through glimmer's own instance hierarchy."
  []
  (:inst @current-mount))

;; --- the delegate ------------------------------------------------------------
(defn- did-finish-launching
  "application:didFinishLaunchingWithOptions: — on the main thread, inside
  UIApplicationMain. Window, root view controller, mount, show."
  [_self _cmd _app _opts]
  (logged "mount"
          (fn []
            (let [win  (u/window-new)
                  vc   (u/view-controller-new)
                  view (u/controller-view vc)]
              (u/set-background! view (if-let [c @root-background] (u/color-hex c) (u/system-background-color)))
              (u/window-root-controller! win vc)
              (reset! current-mount (@pending-mount view :window))
              (force scheduler)                 ; main thread, before anyone posts
              (u/window-make-key! win)
              (reset! window win))))
  1)                                            ; BOOL YES, as :uint8

(defonce ^:private did-finish-cb
  (delay
    (ffi/foreign-callable did-finish-launching
                          [:pointer :pointer :pointer :pointer] :uint8 :collect-safe)))

;; --- lifecycle ---------------------------------------------------------------
;; UIKit tells the delegate when the app leaves and returns to the foreground.
;; Each message is logged with a timestamp and handed to any registered handler
;; — persistence hooks :background, an nREPL might re-listen on :foreground.
(defonce ^:private lifecycle-handlers (atom {}))   ; event -> (fn [])

(defn on-lifecycle!
  "Register `f` (zero-arg) for `event`: :resign-active :background :foreground
  :active :terminate. One handler per event; nil removes."
  [event f]
  (swap! lifecycle-handlers (if f #(assoc % event f) #(dissoc % event)))
  nil)

(defn- lifecycle! [event]
  (println (str "glimmer-uikit: " (name event) " @ " (System/currentTimeMillis)))
  (when-let [f (get @lifecycle-handlers event)]
    (logged "lifecycle handler" f))
  0)

(def ^:private lifecycle-selectors
  {:resign-active "applicationWillResignActive:"
   :background    "applicationDidEnterBackground:"
   :foreground    "applicationWillEnterForeground:"
   :active        "applicationDidBecomeActive:"
   :terminate     "applicationWillTerminate:"})

(defonce ^:private lifecycle-cbs
  (delay
    (into {}
          (for [[event _] lifecycle-selectors]
            [event (ffi/foreign-callable (fn [_self _cmd _app] (lifecycle! event))
                                         [:pointer :pointer :pointer] :void :collect-safe)]))))

(defn- register-delegate-class!
  "UIApplicationMain instantiates the delegate by class name, so the class must
  exist before the call. Idempotent."
  []
  (u/ensure-class! "GlimmerAppDelegate" "NSObject"
                   (fn [c]
                     (u/class-add-method c (u/sel "application:didFinishLaunchingWithOptions:")
                                         @did-finish-cb "c@:@@")
                     (doseq [[event selector] lifecycle-selectors]
                       (u/class-add-method c (u/sel selector) (get @lifecycle-cbs event) "v@:@"))))
  nil)

;; --- the app loop ------------------------------------------------------------
(defn- run*
  "Backend entry point for glimmer.core/run. Loads UIKit, registers the
  delegate, sets loop-running? and enters UIApplicationMain — which never
  returns. Of `opts`, only :background (a \"#rrggbb\" for the root view) means
  anything on a phone; :title and the rest are ignored."
  [opts mount-root!]
  (u/load-uikit!)
  (register-delegate-class!)
  (reset! pending-mount mount-root!)
  (reset! root-background (:background opts))
  (reset! b/loop-running? true)                 ; before the loop, so the mount sees it
  (try
    (u/run-application! "GlimmerAppDelegate")
    (finally (reset! b/loop-running? false))))

;; --- the backend -------------------------------------------------------------
(def backend
  "The UIKit backend map handed to glimmer.backend/register!."
  {:name           :ios
   :create!        w/create!
   :apply-props!   w/apply-props!
   :append-child!  w/append-child!
   :remove-child!  w/remove-child!
   :replace-child! w/replace-child!
   :reorder-child! w/reorder-child!
   :schedule       post-to-gui
   :run            run*})

(defn install!
  "Make UIKit the backend glimmer renders with. Called on load, so requiring
  this namespace is enough."
  []
  (b/register! backend)
  nil)

(defonce ^:private _installed (do (install!) true))
