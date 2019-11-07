(ns chisel.trace-test
  (:require [clojure.test :refer :all]
            [chisel.trace :as trace]
            [io.pedestal.log :as plog]
            [clojure.core.async :as a]
            [chisel.async-utils :as async]
            [taoensso.timbre :as timbre]
            [chisel.test-utils :as test-utils]
            [clj-http.client :as http]
            [chisel.correlation-ctx :as ctx]
            [ring.util.response :as r]
            [io.pedestal.interceptor.helpers :as interceptor])
  (:import (io.opentracing Span)))

(timbre/set-level! :info)

(def port (test-utils/get-free-port))
(def location (str "http://localhost:" port))

(deftest with-span
  (testing "creates span"
    (let [capture (atom nil)]
      (with-redefs [plog/span (fn [op _]
                                (reset! capture op)
                                nil)]
        (trace/with-span "my-span"
                         (is (= "my-span" @capture)))
        (is (= "my-span" @capture))

        (trace/with-span "another-span"
                         (is (= "another-span" @capture)))
        (is (= "another-span" @capture)))))

  (testing "finalises span on normal return"
    (let [capture (atom 0)]
      (with-redefs [plog/finish-span (fn [_]
                                       (swap! capture inc))]
        (trace/with-span "another-span"
                         (is (zero? @capture)))
        (is (= 1 @capture)))))

  (testing "finalises span on exception"
    (let [capture (atom 0)
          caught  (atom 0)]
      (with-redefs [plog/finish-span (fn [_]
                                       (swap! capture inc))]
        (try
          (trace/with-span "failed-span"
                           (throw (ex-info "Mock Exception" {})))
          (catch Exception _
            (swap! caught inc)))
        (is (= 1 @caught))
        (is (= 1 @capture))))))

(deftest go-with-span
  (testing "creates span"
    (let [capture (atom nil)]
      (with-redefs [plog/span (fn [op _]
                                (reset! capture op)
                                nil)]
        (a/<!! (trace/go-with-span "my-span"
                                   (is (= "my-span" @capture))))
        (is (= "my-span" @capture)))))

  (testing "finalises span on normal return"
    (let [capture (atom 0)]
      (with-redefs [plog/finish-span (fn [_]
                                       (swap! capture inc))]
        (a/<!! (trace/go-with-span "another-span"
                                (is (zero? @capture))))
        (is (= 1 @capture)))))

  (testing "finalises span on exception"
    (let [capture (atom 0)
          caught  (atom 0)]
      (with-redefs [plog/finish-span (fn [_]
                                       (swap! capture inc))]
        (try
          (async/<!!?
            (trace/go-with-span "failed-span"
                             (throw (ex-info "Mock Exception" {}))))
          (catch Exception _
            (swap! caught inc)))
        (is (= 1 @caught))
        (is (= 1 @capture))))))

(deftest tracing-interceptor
  (testing "tracing interceptor adds and finishes a span"
    (let [propagated (atom nil)
          finished (atom nil)]
      (with-redefs [plog/finish-span (fn [s] (reset! finished s))]
        (test-utils/with-running-server {:port        port
                                         :route       (interceptor/handler ::ok
                                                                           (fn [request]
                                                                             (when-let [span (::trace/span request)]
                                                                               (reset! propagated span))
                                                                             (r/response "OK")))
                                         :interceptor [ctx/interceptor
                                                       trace/tracing-interceptor
                                                       trace/tracing-ctx-interceptor]}
                                        (http/get location)
                                        (is (instance? Span @propagated))
                                        (is (instance? Span @finished))))))

  (testing "tracing ctx interceptor adds extra span tags on 200"
    (let [tags (atom {})]
      (with-redefs [plog/tag-span (fn [s k & v]
                                    (if v
                                      (swap! tags merge {k (first v)})
                                      (swap! tags merge k))
                                    s)
                    plog/finish-span (fn [_] nil)]
        (test-utils/with-running-server {:port        port
                                         :route       test-utils/ok
                                         :interceptor [ctx/interceptor
                                                       trace/tracing-interceptor
                                                       trace/tracing-ctx-interceptor]}
                                        (http/get location {:headers {"X-Flow-Id" "test-id"}})

                                        (is (= "test-id" (get @tags "flow_id")))
                                        (is (= "pedestal" (get @tags "component")))
                                        (is (= "server" (get @tags "span.kind")))
                                        (is (= "localhost" (get @tags "http.host")))
                                        (is (= "get" (get @tags "http.method")))
                                        (is (= "/" (get @tags "http.url")))
                                        (is (= 200 (get @tags "http.status_code")))))))

  (testing "tracing ctx interceptor adds extra span tags on 500"
    (let [tags (atom {})]
      (with-redefs [plog/tag-span (fn [s k & v]
                                    (if v
                                      (swap! tags merge {k (first v)})
                                      (swap! tags merge k))
                                    s)
                    plog/finish-span (fn [_] nil)]
        (test-utils/with-running-server {:port        port
                                         :route       (interceptor/handler ::server-error
                                                                           (fn [_request]
                                                                             {:status 500 :body "Server Error"}))
                                         :interceptor [ctx/interceptor
                                                       trace/tracing-interceptor
                                                       trace/tracing-ctx-interceptor]}
                                        (http/get location {:headers {"X-Flow-Id" "test-id"}
                                                            :throw-exceptions false})

                                        (is (= "test-id" (get @tags "flow_id")))
                                        (is (= "pedestal" (get @tags "component")))
                                        (is (= "server" (get @tags "span.kind")))
                                        (is (= "localhost" (get @tags "http.host")))
                                        (is (= "get" (get @tags "http.method")))
                                        (is (= "/" (get @tags "http.url")))
                                        (is (= 500 (get @tags "http.status_code")))))))

  (testing "tracing ctx interceptor adds extra span tags on failure"
    (let [tags (atom {})]
      (with-redefs [plog/tag-span (fn [s k & v]
                                    (if v
                                      (swap! tags merge {k (first v)})
                                      (swap! tags merge k))
                                    s)
                    plog/finish-span (fn [_] nil)]
        (test-utils/with-running-server {:port        port
                                         :route       (interceptor/handler ::service-failed
                                                                           (fn [_request]
                                                                             (throw (ex-info "Mock Exception" {}))))
                                         :interceptor [ctx/interceptor
                                                       trace/tracing-interceptor
                                                       trace/tracing-ctx-interceptor]}
                                        (http/get location {:headers {"X-Flow-Id" "test-id"}
                                                            :throw-exceptions false})

                                        (is (= "test-id" (get @tags "flow_id")))
                                        (is (= "pedestal" (get @tags "component")))
                                        (is (= "server" (get @tags "span.kind")))
                                        (is (= "localhost" (get @tags "http.host")))
                                        (is (= "get" (get @tags "http.method")))
                                        (is (= "/" (get @tags "http.url")))
                                        (is (= true (get @tags "error")))
                                        (is (= 500 (get @tags "http.status_code"))))))))
