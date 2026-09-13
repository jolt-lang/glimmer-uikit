(ns glimmer-uikit.ffi-test
  "Headless tests for the pure helpers in the FFI layer. No UIKit needed."
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer-uikit.ffi :as u]))

(deftest hex->rgb-reads-long-and-short-hex
  (testing "#rrggbb gives each channel from 0 to 1"
    (is (= [1.0 0.0 0.0] (u/hex->rgb "#ff0000")))
    (is (= [0.0 1.0 0.0] (u/hex->rgb "#00FF00"))))
  (testing "#rgb doubles each digit"
    (is (= [0.0 0.0 1.0] (u/hex->rgb "#00f"))))
  (testing "a digit that is not hex throws"
    (is (thrown? Exception (u/hex->rgb "#gg0000")))))

(deftest framework-path-names-a-system-framework
  (is (= "/System/Library/Frameworks/UIKit.framework/UIKit"
         (u/framework-path "UIKit"))))
