(ns glimmer-uikit.test-runner-test
  "Tests for the test runner's count of failures."
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer-uikit.test-runner :as runner]))

(deftest failures-count-assertions-that-fail-or-err
  (is (= 0 (runner/failures {:fail 0 :error 0} [])))
  (is (= 3 (runner/failures {:fail 1 :error 2} []))))

(deftest failures-count-a-namespace-that-did-not-load
  (testing "a test namespace that does not load runs no tests, so it counts once"
    (is (= 1 (runner/failures {:fail 0 :error 0} '[glimmer-uikit.broken-test]))))
  (testing "results with no counts at all, when no namespace loaded"
    (is (= 2 (runner/failures {} '[a-test b-test])))))
