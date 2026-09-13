(ns glimmer-uikit.test-runner
  "Entry point for `jolt -M:test`. Requires each glimmer-uikit test namespace and
  runs clojure.test against it. Prints a summary; exits non-zero if anything
  failed (so the :test task fails CI)."
  (:require [clojure.test :as t]))

;; Print the cause chain of a test that throws. jolt's run-tests reports a
;; thrown test through clojure.test/err!, not the report multimethod, and err!
;; prints only the outer exception (stdlib/clojure/test.clj, jolt v0.8.7).
;; clj-kondo knows only the JVM clojure.test, which has no err!.
#_{:clj-kondo/ignore [:unresolved-var]}
(alter-var-root #'t/err!
  (fn [err!]
    (fn [m]
      (err! m)
      (t/with-test-out
        (loop [c (some-> (:actual m) ex-cause)]
          (when c
            (println "  caused by:" (.getName (class c)) ":" (ex-message c))
            (recur (ex-cause c))))))))

;; Call System/exit directly. (resolve 'System/exit) is nil on jolt 0.8.7,
;; so a resolve-based exit did nothing and a failed run exited 0.
(defn- exit [code]
  (System/exit code))

(defn -main [& _]
  (let [namespaces '[glimmer-uikit.widget-test]]
    (doseq [ns namespaces]
      (try (require ns :reload)
           (catch Exception e
             (println "ERROR requiring" ns ":" (ex-message e)))))
    (let [results (apply t/run-tests namespaces)
          failed (+ (:fail results 0) (:error results 0))]
      (println "----")
      (println "tests:" (:test results 0)
               "assertions:" (:pass results 0) "passed /"
               failed "failed")
      (when (pos? failed) (exit 1)))))
