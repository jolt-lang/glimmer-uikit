(ns glimmer-ios.counter
  "A reactive counter — the canonical reagent-style demo, over UIKit.

  The count lives in a reactive atom. Tapping a button swaps it, and glimmer
  re-renders the label in place. The atom is a top-level var so that
  glimmer-ios.smoke can read it."
  (:require [glimmer.ratom :as r]
            [glimmer.core :as ui]
            [glimmer-ios.core]))   ; installs the UIKit backend

(defonce clicks (r/atom 0))

(defn counter []
  [:vbox {:spacing 12}
   [:label {:label (str "Count: " @clicks)}]
   [:hbox {:spacing 8}
    [:button {:label "- 1" :on-click #(swap! clicks dec)}]
    [:button {:label "+ 1" :on-click #(swap! clicks inc)}]
    [:button {:label "reset" :on-click #(reset! clicks 0)}]]])

(defn -main [& _]
  (ui/run counter))
