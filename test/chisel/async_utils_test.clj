(ns chisel.async-utils-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [chisel.async-utils :as async-utils]
            [chisel.test-utils :as test-utils]))

(deftest go-blocks

  (testing "go-try catches"
    (let [ex    (ex-info "sample exception" {})
          block (async-utils/go-try (throw ex))]
      (is (= ex (test-utils/<!!-with-timeout block)))))


  (testing "go-let catches too"
    (let [ex    (ex-info "sample exception" {})
          block (async-utils/go-let [binded (throw ex)]
                  binded)]
      (is (= ex (test-utils/<!!-with-timeout block)))))


  (testing "thread-try catches"
    (let [ex    (ex-info "sample exception" {})
          block (async-utils/thread-try (throw ex))]
      (is (= ex (test-utils/<!!-with-timeout block)))))


  (testing "<? throws (given that go-try catches)"
    (let [ex      (ex-info "sample exception" {})
          ex-chan (a/go ex)
          block   (async-utils/go-try (async-utils/<? ex-chan))]
      (is (= ex (test-utils/<!!-with-timeout block)))))


  (testing "<? passes chan value when no exception"
    (let [chan (a/go "foo")]
      (is (= "foo" (a/<!! (a/go (async-utils/<? chan)))))))


  (testing "<!? throws"
    (let [ex      (ex-info "sample exception" {})
          ex-chan (a/go ex)]
      (is (test-utils/thrown? ex (async-utils/<!!? ex-chan)))))


  (testing "<!? passes chan value when no exception"
    (let [chan (a/go "foo")]
      (is (= "foo" (async-utils/<!!? chan)))))


  (testing "<!* syncs over vector of channels"
    (let [a-chan (a/go (a/<! (a/timeout 10)) ::a)
          b-chan (a/go (a/<! (a/timeout 20)) ::b)
          c-chan (a/go (a/<! (a/timeout 30)) ::c)
          result (test-utils/<!!-with-timeout (async-utils/<!* [c-chan b-chan a-chan]))]
      (is (= [::c ::b ::a] result))))


  (testing "<!* doesn't hang on an empty input"
    (is (= [] (test-utils/<!!-with-timeout (async-utils/<!* [])))))


  (testing "<!!* syncs over vector of channels"
    (let [a-chan (a/go (a/<! (a/timeout 10)) ::a)
          b-chan (a/go (a/<! (a/timeout 20)) ::b)
          c-chan (a/go (a/<! (a/timeout 30)) ::c)
          result (async-utils/<!!* [c-chan b-chan a-chan] 1000)]
      (is (= [::c ::b ::a] result))))


  (testing "<!* doesn't hang on an empty input"
    (is (= [] (async-utils/<!!* [] 1000))))


  (testing "<!!* fails completely when timeout reached"
    (let [a-chan (a/go (a/<! (a/timeout 10)) ::a)
          b-chan (a/go (a/<! (a/timeout 200)) ::b)
          c-chan (a/go (a/<! (a/timeout 300)) ::c)
          result (async-utils/<!!* [c-chan b-chan a-chan] 100)]
      (is (= nil result))))


  (testing "<some! gives most of results, order is preserved"
    (let [a-chan (a/go (a/<! (a/timeout 10)) ::a)
          b-chan (a/go (a/<! (a/timeout 20)) ::b)
          c-chan (a/go (a/<! (a/timeout 300)) ::c)
          result (test-utils/<!!-with-timeout (async-utils/<some! [c-chan b-chan a-chan] 100))]
      (is (= [::b ::a] result))))


  (testing "<some!! gives most of results, order is preserved"
    (let [a-chan (a/go (a/<! (a/timeout 10)) ::a)
          b-chan (a/go (a/<! (a/timeout 20)) ::b)
          c-chan (a/go (a/<! (a/timeout 300)) ::c)
          result (async-utils/<some!! [c-chan b-chan a-chan] 100)]
      (is (= [::b ::a] result)))))
