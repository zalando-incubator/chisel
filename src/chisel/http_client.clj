(ns chisel.http-client
  (:refer-clojure :rename {promise core-promise})
  (:require [clojure.core.async :as a]
            [clj-http.client :as http]
            [org.bovinegenius [exploding-fish :as uri]]
            [metrics.timers :as timers]
            [metrics.meters :as meters]
            [chisel.metrics :as metrics]
            [chisel.correlation-ctx :as correlation-ctx]
            [chisel.logging :as log]
            [io.pedestal.log :as plog]
            [chisel.trace :as trace]
            [clojure.string :as string])
  (:import (java.util.concurrent TimeUnit)
           (net.jodah.failsafe CircuitBreaker)
           (io.opentracing Tracer Span)
           (io.opentracing.propagation Format$Builtin TextMapInjectAdapter)
           (java.util HashMap)))


(def metric-prefix "egress")


(defn- make-route-name
  "Generates metric name from route name
  :namespace/name -> namespace.name
  or if route name if missing, takes hostname from location"
  [route-name location]
  (if route-name
    (if-let [ns-str (namespace route-name)]
      (str ns-str "." (name route-name))
      (name route-name))
    (:host (uri/uri location))))


(defn- mark-meter
  "Shorthand to add mark to a metric"
  [title]
  (meters/mark! (meters/meter metrics/metric-registry title)))



(defn- success-callback-fn [on-success ^CircuitBreaker breaker service-name route-name span begin]
  (let [captured-ctx correlation-ctx/*ctx*]                 ; capture dynamic context
    (fn [response]                                          ; because clj-http will loose it
      (binding [correlation-ctx/*ctx* captured-ctx]         ; and restore it in callback
        (let [end      (System/currentTimeMillis)
              duration (- end begin)
              status   (:status response)
              timer    (timers/timer metrics/metric-registry [metric-prefix route-name (str status)])]
          (when breaker (.recordSuccess breaker))
          (.update timer duration (TimeUnit/MILLISECONDS))
          (log/info :msg "Remote call"
                    :service service-name
                    :route route-name
                    :status status
                    :duration duration)
          (-> span
              (plog/tag-span "http.status_code" status)
              (plog/finish-span))
          (on-success response))))))


(defn- failure-callback-fn [on-failure ^CircuitBreaker breaker service-name route-name span fallback-handler retry-fn]
  (let [captured-ctx correlation-ctx/*ctx*]                 ; capture dynamic context
    (fn [^Exception exception]                              ; because clj-http will loose it
      (binding [correlation-ctx/*ctx* captured-ctx]         ; and restore it in callback
        (let [msg (or (.getMessage exception) "none")]
          (-> span
              (trace/log-span
                :service service-name
                :route route-name)
              (trace/log-span exception)
              (plog/tag-span "error" true)
              (plog/finish-span))
          (log/error :msg "Remote call failed"
                     :service service-name
                     :route route-name
                     :error-msg msg))
        (if retry-fn
          (retry-fn)
          (do
            (when breaker (.recordFailure breaker exception))
            (mark-meter [metric-prefix route-name "fail"])
            (if fallback-handler
              (fallback-handler)
              (on-failure exception))))))))

(defn- tracing-headers
  "Collect the tracing headers for sending to dependencies"
  [^Span span]
  (let [headers (HashMap.)]
    (.inject ^Tracer plog/default-tracer
             (.context span)
             Format$Builtin/HTTP_HEADERS
             (TextMapInjectAdapter. headers))
    headers))

(defn circuit-closed?
  [^CircuitBreaker breaker]
  (or (nil? breaker)
      (and breaker (.allowsExecution breaker))))


(defn async
  "A wrapper around clj-http that adds:
  - logs response code and duration of the call
  - reports per-endpoint metrics about rates and latency

  Additional arguments that could be passed through opts:
  - ::http/service-name will be used for logging and tracing (if not set, a service name will be extracted from the url)
  - ::http/route-name will be used for logging, tracing and for metrics (surrounded with common prefix and status)
  - ::http/circuit-breaker to enable the pattern
  - ::http/fallback to call when circuit is open or exception happened (timeout)
  - ::http/retries if you want to retry on failure"
  [method location opts on-success on-failure]
  (http/check-url! location)
  (let [begin-ts         (System/currentTimeMillis)
        headers          (cond-> (:headers opts)
                                 (correlation-ctx/defined?)
                                 (merge correlation-ctx/*ctx*))
        route-name       (make-route-name (::route-name opts) location)
        host             (uri/host (uri/uri location))
        service-name     (get opts ::service-name (when host
                                                    (first (string/split host #"\."))))
        retries          (get opts ::retries 0)
        retry            (get opts ::retry false)
        span             (-> (plog/span route-name (trace/current-span))
                             (plog/tag-span "http.method" (string/upper-case (name method))
                                            "http.url" location
                                            "retries" retries
                                            "retry" retry
                                            "peer.hostname" host
                                            "peer.service" service-name
                                            "component" "chisel"
                                            "span.kind" "client"))
        params           (merge opts {:url     location
                                      :method  method
                                      :headers (merge headers
                                                      (tracing-headers span))
                                      :async?  true})
        breaker          ^CircuitBreaker (::circuit-breaker opts)
        fallback-handler (when-let [fallback (::fallback opts)]
                           #(do
                              (mark-meter [metric-prefix route-name "fallback"])
                              (on-success (fallback))))
        retry-fn         (when (pos? retries)
                           ;; build retry-fn, if there are retries left, decreasing count
                           #(async method location (assoc (update opts ::retries dec) ::retry true) on-success on-failure))]
    (if (circuit-closed? breaker)
      (http/request params
                    (success-callback-fn on-success breaker service-name route-name span begin-ts)
                    (failure-callback-fn on-failure breaker service-name route-name span fallback-handler retry-fn))
      (do
        (mark-meter [metric-prefix route-name "opened"])
        (-> span
            (trace/log-span
              :message "Circuit Breaker opened"
              :service service-name
              :route route-name
              :event "cb-open")
            (plog/tag-span "error" true)
            (plog/finish-span))
        (if fallback-handler
          (fallback-handler)
          (on-failure (ex-info "Open circuit-breaker, but no fallback" {})))))))


(defn chan
  ([method location] (chan method location {}))
  ([method location opts]
   (let [result     (a/chan)
         on-success (fn [resp]
                      (a/put! result resp)
                      (a/close! result))
         on-failure (fn [ex]
                      (a/put! result ex)
                      (a/close! result))]
     (async method location opts on-success on-failure)
     result)))


(comment

  (require '[diehard.core :as dh]
           '[clj-http.conn-mgr :as conn-mgr])

  (dh/defcircuitbreaker deeplinks-breaker
                        {:failure-threshold-ratio [8 10]
                         :delay-ms                1000})

  (def ^:private connection-manager
    (conn-mgr/make-reuseable-async-conn-manager
      {:threads           100
       :default-per-route 100}))

  @(promise :get "https://httpstatuses.com/200")

  (log/replace-formatter)

  (a/go (prn @(promise :get "https://api.fashion-store-test.zalan.do/graphql"
                       {})))

  (async :get "https://httpstatuses.com/200"
         {:connection-manager connection-manager}
         println
         println)

  clj-http.client/default-middleware

  (a/<!! (chan :get "https://httpstatuses.com/200" {}))

  )
