(ns glimmer-uikit.smoke
  "Runs the counter and taps + 1 twice from a worker thread, through the
  backend's scheduler. simctl cannot tap, so the app taps itself and prints
  the count. Expected on the console: smoke: count = 2"
  (:require [glimmer-uikit.counter :as counter]
            [glimmer.core :as ui]
            [glimmer-uikit.core :as ios]
            [glimmer-uikit.ffi :as u]))

(defn- find-native
  "Search glimmer's instance tree, depth first, for a :native node that
  matches pred."
  [inst-atom pred]
  (let [inst @inst-atom]
    (if (and (= :native (:type inst)) (pred inst))
      inst
      (some #(find-native % pred) (:children inst)))))

(defn- tap-plus-one-twice! []
  (if-let [button (find-native (ios/root-inst)
                               #(and (= :button (:tag %)) (= "+ 1" (:label (:props %)))))]
    (do (u/send-actions! (:widget button) u/EVENT-TOUCH-UP-INSIDE)
        (u/send-actions! (:widget button) u/EVENT-TOUCH-UP-INSIDE)
        (println "smoke: count =" @counter/clicks))
    (println "smoke: no + 1 button in the instance tree")))

(defn -main [& _]
  (future
    (Thread/sleep 1000)
    (println "smoke: posting from a worker thread")
    (ios/schedule! tap-plus-one-twice!))
  (ui/run counter/counter))
