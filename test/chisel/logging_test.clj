(ns chisel.logging-test
  (:require [clojure.test :refer :all]
            [taoensso.timbre :as timbre]
            [chisel.logging :as log]
            [chisel.correlation-ctx :as ctx]
            [io.pedestal.log :as pedestal-log]))


(timbre/set-level! :info)


(deftest lib-integration

  (testing "When timbre is used as backend, it gets called on logs"
    (let [log-args  (atom nil)
          log-level (atom nil)]
      (with-redefs [pedestal-log/log (fn [keyvals level]
                                       (reset! log-level level)
                                       (reset! log-args keyvals))]
        (log/debug :msg "test-message")
        (is (= :debug @log-level))
        (is (= "test-message" (:msg @log-args))))))

  (testing "When captured, request context is propagated"
    (let [log-args  (atom nil)
          log-level (atom nil)
          context   {:key "value"}]
      (with-redefs [pedestal-log/log (fn [keyvals level]
                                       (reset! log-level level)
                                       (reset! log-args keyvals))]
        (ctx/with-context {::ctx/ctx context}
          (log/debug :msg "test-message")
          (is (= :debug @log-level))
          (is (= context (:context @log-args)))))))

  (testing "When specified, filtering is applied to the context"
    (let [log-args  (atom nil)
          log-level (atom nil)]
      (with-redefs [pedestal-log/log (fn [keyvals level]
                                       (reset! log-level level)
                                       (reset! log-args keyvals))]
        (log/set-context-filter! #(dissoc % :private))
        (ctx/with-context {::ctx/ctx {:public  "public-value"
                                      :private "private-value"}}
          (prn :ctx ctx/*ctx*)
          (log/debug :msg "test-message")
          (is (= :debug @log-level))

          (is (= {:public "public-value"}
                 (:context @log-args)))))))

  (testing "Log filtering is not applied to the global context"
    (let [log-args  (atom nil)
          log-level (atom nil)]
      (with-redefs [pedestal-log/log (fn [keyvals level]
                                       (reset! log-level level)
                                       (reset! log-args keyvals))]
        (log/set-context-filter! #(dissoc % :private))
        (prn :ctx ctx/*ctx*)
        (log/debug :msg "test-message")
        (is (= :debug @log-level))

        (is (= ::ctx/global
               (:context @log-args)))))))
