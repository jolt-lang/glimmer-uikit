(ns glimmer-uikit.core-test
  "Headless tests for the app loop's pure helpers. No UIKit needed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [glimmer-uikit.core :as core]))

(deftest logged-returns-what-the-work-returns
  (is (= 3 (#'core/logged "work" (fn [] 3)))))

(deftest logged-prints-a-failure-and-returns-nil
  (let [result (atom :unset)
        out    (with-out-str
                 (reset! result (#'core/logged "work" #(throw (ex-info "boom" {})))))]
    (is (nil? @result))
    (is (str/includes? out "glimmer-uikit: work failed:"))))
