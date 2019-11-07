(ns chisel.http-client-test
  (:require [clojure.test :refer :all]
            [io.pedestal.log :as pedestal-log]
            [taoensso.timbre :as timbre]
            [clj-http.client :as original-http]
            [metrics.core :as metrics-core]
            [cheshire.core :as json]
            [diehard.circuit-breaker :as circuit-breaker]
            [chisel.metrics :as routes-metrics]
            [chisel.http-client :as http]
            [chisel.test-utils :refer [<!!-with-timeout]]
            [io.pedestal.log :as plog])
  (:import (clojure.lang ExceptionInfo)))

(defn fake-request-success [req respond _raise]
  (respond {:status 200 :body {:req req}}))

(defn fake-request-failure [req respond _raise]
  (respond {:status 500 :body {:req req}}))

(defn fake-request-throw [req _respond raise]
  (raise (ex-info "Controlled explosion from tests" {:req req})))


(defn do-nothing-fn [& _])


(deftest callbacks

  (testing "Success function is called"
    (let [captured-result (atom nil)]
      (with-redefs [original-http/request fake-request-success]

        (http/async :get "http://example.com" {}
                    #(reset! captured-result %)
                    do-nothing-fn)

        (let [{:keys [status body]} @captured-result
              req (-> body :req)]
          (is (= 200 status))
          (is (= :get (:method req)))
          (is (= "http://example.com" (:url req)))))))

  (testing "Failure function is called on exception"
    (let [captured-result (atom nil)
          error-span      (atom false)]
      (with-redefs [plog/tag-span         (fn [s k v & _rest]
                                            (when (= k "error")
                                              (reset! error-span v))
                                            s)
                    original-http/request fake-request-throw]

        (http/async :get "http://example.com" {}
                    do-nothing-fn
                    #(reset! captured-result %))

        (let [res @captured-result
              req (-> res ex-data :req)]
          (is (= :get (:method req)))
          (is (= "http://example.com" (:url req)))
          (is (true? @error-span))))))

  (testing "Fallback function is called on exception when exists"
    (let [captured-result (atom nil)
          error-span      (atom false)]
      (with-redefs [plog/tag-span         (fn [s k v & _rest]
                                            (when (= k "error")
                                              (reset! error-span v))
                                            s)
                    original-http/request fake-request-throw]

        (http/async :get "http://example.com"
                    {::http/fallback (constantly {:status 503
                                                  :body   "Fallback function"})}
                    #(reset! captured-result %)
                    do-nothing-fn)

        (let [{:keys [status body]} @captured-result]
          (is (= 503 status))
          (is (= "Fallback function" body))
          (is (true? @error-span))))))

  (testing "Failure function is called on failure"
    (let [captured-result (atom nil)
          error-span      (atom false)]
      (with-redefs [plog/tag-span         (fn [s k v & _rest]
                                            (when (= k "error")
                                              (reset! error-span v))
                                            s)
                    original-http/request fake-request-failure]

        (http/async :get "http://example.com" {}
                    #(reset! captured-result %)
                    do-nothing-fn)

        (let [{:keys [status body]} @captured-result
              req (-> body :req)]
          (is (= 500 status))
          (is (= :get (:method req)))
          (is (= "http://example.com" (:url req)))
          (is (false? @error-span)))))))

(deftest side-effects

  (testing "When request is successful, 200 status is logged as info"
    (let [log-args  (atom nil)
          log-level (atom nil)]
      (with-redefs [original-http/request fake-request-success
                    pedestal-log/log      (fn [keyvals level]
                                            (reset! log-level level)
                                            (reset! log-args keyvals))]
        (<!!-with-timeout (http/chan :get "http://example.com" {}))
        (is (= :info @log-level))
        (is (= 200 (:status @log-args)))
        (is (= "example.com" (:route @log-args))))))

  (testing "When request fails, then error is logged"
    (let [log-args  (atom nil)
          log-level (atom nil)]
      (with-redefs [original-http/request fake-request-throw
                    pedestal-log/log      (fn [keyvals level]
                                            (reset! log-level level)
                                            (reset! log-args keyvals))]
        (<!!-with-timeout (http/chan :get "http://example.com" {}))
        (is (= "Remote call failed" (:msg @log-args)))
        (is (= "Controlled explosion from tests" (:error-msg @log-args))))))

  (testing "Logging is called (timbre used as backend) in *log-success-fn*"
    (let [call-count (atom 0)]
      (with-redefs [original-http/request fake-request-success
                    timbre/-log!          (fn [& _] (swap! call-count inc))]
        (<!!-with-timeout (http/chan :get "http://example.com" {}))
        ;; simply checks that logging was called
        (is (< 0 @call-count)))))

  (testing "Metrics are reported to the request-ctx/*registry*"
    (with-redefs [routes-metrics/metric-registry (metrics-core/new-registry)
                  original-http/request          fake-request-success]
      (<!!-with-timeout (http/chan :get "http://example.com" {}))
      ;; checks that this call is counted an available through request-ctx/handler
      (let [values      (-> (routes-metrics/handler nil)
                            :body
                            (json/parse-string true))
            total-count (-> values
                            :egress.example.com.200
                            :rates
                            :total)]
        (is (= 1 total-count))))))


(deftest circuit-breakers

  (testing "Given certain amount of failed responses, circuit breaker opens and fallback is used"
    (let [breaker    (circuit-breaker/circuit-breaker {:failure-threshold-ratio [1 2]
                                                       :delay-ms                10000})
         result     (atom nil)
         error-span (atom false)]
      (with-redefs [plog/tag-span         (fn [s k v & _rest]
                                            (when (= k "error")
                                              (reset! error-span v))
                                            s)
                    original-http/request fake-request-throw]
        (http/async :get "http://example.com" {::http/circuit-breaker breaker} do-nothing-fn do-nothing-fn)
        (http/async :get "http://example.com" {::http/circuit-breaker breaker} do-nothing-fn do-nothing-fn)
        (http/async :get "http://example.com"
                    {::http/fallback        (constantly ::fallback)
                     ::http/circuit-breaker breaker}
                    #(reset! result %)
                    do-nothing-fn)
        (is (= @result ::fallback))
        (is (true? @error-span)))))

  (testing "Given open circuit-breaker and lack of fallback, artificial exception is passed to on-failure"
    (let [breaker (circuit-breaker/circuit-breaker {:failure-threshold-ratio [1 2]
                                                    :delay-ms                10})
          result  (atom nil)]
      (with-redefs [original-http/request fake-request-throw]
        (http/async :get "http://example.com" {::http/circuit-breaker breaker} do-nothing-fn do-nothing-fn)
        (http/async :get "http://example.com" {::http/circuit-breaker breaker} do-nothing-fn do-nothing-fn)
        (http/async :get "http://example.com"
                    {::http/circuit-breaker breaker}
                    do-nothing-fn
                    #(reset! result %)))
      (is (instance? ExceptionInfo @result))))

  (testing "Given success response after circuit was open, it closes and response is returned"
    (let [breaker (circuit-breaker/circuit-breaker {:failure-threshold-ratio [1 2]
                                                    :delay-ms                10})
          result  (atom nil)]
      (with-redefs [original-http/request fake-request-throw]
        (http/async :get "http://example.com" {::http/circuit-breaker breaker} do-nothing-fn do-nothing-fn)
        (http/async :get "http://example.com" {::http/circuit-breaker breaker} do-nothing-fn do-nothing-fn))
      (Thread/sleep 100)
      (with-redefs [original-http/request fake-request-success]
        (http/async :get "http://example.com"
                    {::http/fallback        (constantly ::fallback)
                     ::http/circuit-breaker breaker}
                    #(reset! result %)
                    do-nothing-fn)
        (is (= 200 (:status @result)))))))


(deftest retries

  (testing "Call is retied if failure-fn was called and retries specified"
    (let [request-count (atom 0)]
      (with-redefs [original-http/request
                    (fn [req _respond raise]
                      (swap! request-count inc)
                      (raise (ex-info "Controlled explosion from tests" {:req req})))]

        (http/async :get "http://example.com" {::http/retries 2}
                    do-nothing-fn
                    do-nothing-fn)

        (is (= 3 @request-count)))))

  (testing "Call is not retied when retries are not specified"
    (let [request-count (atom 0)]
      (with-redefs [original-http/request
                    (fn [req _respond raise]
                      (swap! request-count inc)
                      (raise (ex-info "Controlled explosion from tests" {:req req})))]

        (http/async :get "http://example.com" {}
                    do-nothing-fn
                    do-nothing-fn)

        (is (= 1 @request-count))))))


(deftest channels

  (testing "Success is propagated to a channel"
    (let [response {:status 200 :body ""}]
      (with-redefs [original-http/request (fn [_req respond _raise]
                                            (respond response))]
        (is (= response (<!!-with-timeout (http/chan :get "http://example.com")))))))

  (testing "Failure is propagated to a channel"
    (let [exception (ex-info "Known exception" {})]
      (with-redefs [original-http/request (fn [_req _respond raise]
                                            (raise exception))]
        (is (= exception (<!!-with-timeout (http/chan :get "http://example.com"))))))))
