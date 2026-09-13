(ns glimmer-uikit.widget-test
  "Headless tests for the widget layer's pure functions. No UIKit needed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hiccup2.core :as hiccup]                 ; the oracle; a :test dep only
            [glimmer-uikit.widget :as w]))

(deftest escape-markup-escapes-pango-significant-chars
  (testing "leaves plain text untouched"
    (is (= "no special chars" (w/escape-markup "no special chars"))))
  (testing "escapes ampersand first so later escapes don't double-encode"
    (is (= "a &amp; b" (w/escape-markup "a & b"))))
  (testing "escapes angle brackets"
    (is (= "&lt;tag&gt;" (w/escape-markup "<tag>"))))
  (testing "all three together"
    (is (= "&lt;b&gt;a &amp; b&lt;/b&gt;" (w/escape-markup "<b>a & b</b>"))))
  (testing "and the two quotes, so a value can sit inside an attribute"
    (is (= "&quot;a&quot; &apos;b&apos;" (w/escape-markup "\"a\" 'b'")))))

(deftest markup-renders-pango-from-hiccup
  (testing "span with attributes"
    (is (= "<span foreground=\"#8e939d\">Nothing to do yet</span>"
           (w/markup [:span {:foreground "#8e939d"} "Nothing to do yet"]))))
  (testing "attribute map is optional"
    (is (= "<b>bold</b>" (w/markup [:b "bold"]))))
  (testing "nested elements"
    (is (= "<b><i>x</i></b>" (w/markup [:b [:i "x"]]))))
  (testing "a number child is stringified"
    (is (= "<b>3</b>" (w/markup [:b 3]))))
  (testing "mixed content inside a span"
    (is (= "<span><b>a</b> <i>b</i></span>" (w/markup [:span [:b "a"] " " [:i "b"]]))))
  (testing "link via <a>"
    (is (= "<a href=\"https://example.com\">link</a>"
           (w/markup [:a {:href "https://example.com"} "link"])))))

(deftest markup-escapes-content-and-attribute-values
  (testing "text nodes are escaped"
    (is (= "<b>a &amp; b &lt; c</b>" (w/markup [:b "a & b < c"]))))
  (testing "attribute values escape quotes so the attr can't break out"
    (is (= "<span foreground=\"a&quot;b\">x</span>"
           (w/markup [:span {:foreground "a\"b"} "x"])))))

(deftest markup-string-coerces-prop
  (testing "a string is already markup — passed through verbatim"
    (is (= "<b>x</b>" (w/markup-string "<b>x</b>")))
    (is (= "plain" (w/markup-string "plain"))))
  (testing "hiccup is rendered (and its text escaped) via markup"
    (is (= "<span foreground=\"#888\">a &amp; b</span>"
           (w/markup-string [:span {:foreground "#888"} "a & b"])))))

(deftest with-orientation-injects-from-tag
  (testing ":hbox gets horizontal"
    (is (= {:spacing 8 :orientation :horizontal} (w/with-orientation :hbox {:spacing 8}))))
  (testing ":vbox gets vertical"
    (is (= {:orientation :vertical} (w/with-orientation :vbox {}))))
  (testing "an explicit :orientation in props always wins"
    (is (= {:orientation :vertical} (w/with-orientation :hbox {:orientation :vertical}))))
  (testing "non-box tags are untouched"
    (is (= {:label "x"} (w/with-orientation :button {:label "x"})))
    (is (= {} (w/with-orientation :box {})))))

(deftest markup-rejects-things-pango-cannot-parse
  (testing "unsupported tag (e.g. an HTML-only tag) throws"
    (is (thrown? Exception (w/markup [:div "x"])))
    (is (thrown? Exception (w/markup [:br]))))
  (testing "unknown span attribute (a typo) throws"
    (is (thrown? Exception (w/markup [:span {:forground "#fff"} "x"]))))
  (testing "attributes on an attribute-less tag like <b> throw"
    (is (thrown? Exception (w/markup [:b {:weight "bold"} "x"])))))

(deftest container-kinds
  (testing "boxes and the root hold children; leaves do not"
    (is (= :box (w/container-kind :vbox)))
    (is (= :box (w/container-kind :hbox)))
    (is (= :window (w/container-kind :window)))
    (is (= :none (w/container-kind :label)))
    (is (= :none (w/container-kind :button)))))

(deftest span-style-reads-the-polish-attributes
  (testing "variant smallcaps becomes a style flag"
    (is (= {:smallcaps true} (w/span-style {:variant "smallcaps"}))))
  (testing "the existing attributes are unchanged"
    (is (= {:color "#fff" :size "large" :weight true}
           (w/span-style {:foreground "#fff" :size "large" :weight "bold"}))))
  (testing "an unknown variant is ignored"
    (is (= {} (w/span-style {:variant "normal"}))))
  (testing "letter_spacing in Pango units becomes :kern in points"
    (is (= {:kern 13.0} (w/span-style {:letter_spacing "13312"})))
    (is (= {} (w/span-style {:letter_spacing "wide"})))))

(deftest span-style-reads-a-family-and-a-signed-kern
  (is (= {:family "Helvetica-Bold"} (w/span-style {:font_family "Helvetica-Bold"})))
  (is (= {:kern -1.16} (w/span-style {:letter_spacing "-1187.84"})))
  (is (= {:kern 5.72} (w/span-style {:letter_spacing "5857.28"}))))

(deftest layers-hold-children
  (is (= :layers (w/container-kind :layers)))
  (is (= :none (w/container-kind :image)))
  (is (= :none (w/container-kind :gradient))))

(deftest scroll-holds-children
  (is (= :scroll (w/container-kind :scroll))))

(deftest a-reused-button-takes-the-new-handler
  ;; the widget is a fake pointer: with no :label or :sensitive, the button
  ;; spec's apply touches no UIKit, so this runs on the host
  (let [start (fn [] :start) end (fn [] :end)]
    (w/apply-props! :button 4242 {:on-click start})
    (is (= start (w/handler-for 4242)))
    (w/apply-props! :button 4242 {:on-click end})
    (is (= end (w/handler-for 4242)) "the handler follows the render, not the mount")))

(deftest a-checkbutton-is-a-leaf-that-toggles
  ;; a fake pointer again: :on-toggled is a signal, so the spec's apply
  ;; sees no :active and touches no UIKit
  (let [on (fn [] :on) off (fn [] :off)]
    (is (= :none (w/container-kind :checkbutton)))
    (w/apply-props! :checkbutton 4343 {:on-toggled on})
    (is (= on (w/handler-for 4343)))
    (w/apply-props! :checkbutton 4343 {:on-toggled off})
    (is (= off (w/handler-for 4343)) "the handler follows the render, like a button's")))

(deftest button-font-args-map-props-to-a-system-font
  (testing "size and weight"
    (is (= [20.0 0.4] (w/button-font-args {:font-size 20 :font-weight :bold}))))
  (testing "weight defaults to regular"
    (is (= [34.0 0.0] (w/button-font-args {:font-size 34}))))
  (testing "no size means no font"
    (is (nil? (w/button-font-args {:font-weight :bold})))))

(deftest markup-renders-pango-hiccup-by-hand
  (testing "an element, attributes in name order, text escaped"
    (is (= "<span foreground=\"#fff\" size=\"12288\">a &amp; b</span>"
           (w/markup [:span {:size "12288" :foreground "#fff"} "a & b"]))))
  (testing "nesting, a seq of children, nil dropped, a number as text"
    (is (= "<b>one<i>two</i>3</b>"
           (w/markup [:b "one" (list [:i "two"] nil 3)]))))
  (testing "a true attribute, a nil one, an empty map"
    (is (= "<span fallback=\"fallback\">x</span>" (w/markup [:span {:fallback true :lang nil} "x"])))
    (is (= "<u>x</u>" (w/markup [:u {} "x"])))))

;; The oracle: hiccup itself, a test-only dependency since this walk replaced
;; it in the app. Every form the generator makes — which is every shape the
;; app makes — must render to the same bytes. Text carries the five escaped
;; characters on purpose; attribute values include true, nil, false and a
;; number, so hiccup's three attribute rules are all exercised.
(def ^:private gen-text
  (gen/fmap #(apply str %) (gen/vector (gen/elements ["a" "b" " " "&" "<" ">" "\"" "'" "é"]) 0 6)))

(def ^:private gen-attrs
  (gen/map (gen/elements [:size :foreground :weight :lang :rise])
           (gen/one-of [gen-text (gen/return true) (gen/return nil) (gen/return false) gen/small-integer])
           {:max-elements 3}))

(def ^:private gen-markup
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/fmap (fn [[a kids]] (into [:span a] kids)) (gen/tuple gen-attrs (gen/vector inner 0 3)))
                  (gen/fmap (fn [[tag kids]] (into [tag] kids))
                            (gen/tuple (gen/elements [:b :i :u :s :tt :small :big]) (gen/vector inner 0 3)))]))
   (gen/one-of [gen-text (gen/return nil) gen/small-integer])))

(defspec markup-matches-hiccup 200
  (prop/for-all [form gen-markup]
    (= (w/markup form) (str (hiccup/html form)))))
