(ns chisel.correlation-ctx
  "Provides means to correlate events from same request in logs and calls to external services"
  (:require [io.pedestal.interceptor.helpers :as interceptor]
            [chisel.async-utils :refer [go-let <?]]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async.impl.protocols :as protocols])
  (:import (java.nio ByteBuffer)
           (java.util UUID Base64 Base64$Encoder)))


;; This needs to be bounded before executing a handler
(def ^:dynamic *ctx* ::global)


(defn defined?
  "Checks of context was defined for the request"
  []
  (not= *ctx* ::global))


(def correlation-id-header "x-flow-id")


(def ^Base64$Encoder encoder (Base64/getUrlEncoder))


; Wrapping static function for testing purposes
(defn- random-uuid [] (UUID/randomUUID))


(defn- make-flow-id
  "Copied from zalando/tracer lib FlowIDGenerator impl"
  []
  (let [^UUID uuid (random-uuid)
        ba   (-> (ByteBuffer/wrap (byte-array 16))
                 (.putLong (.getMostSignificantBits uuid))
                 (.putLong (.getLeastSignificantBits uuid))
                 (.array))
        bs   (-> (.encodeToString encoder ba)
                 (.replaceAll "=" "")
                 (.substring 1))]
    (str "R" bs)))


(defn correlation-ctx
  "Constructor for correlation context from the request"
  [request]
  (let [correlation-id (get (:headers request) correlation-id-header
                            (make-flow-id))]
    {correlation-id-header correlation-id}))


(defn make-interceptor
  "Extract the headers from the request, filter by the context keys we want to keep.
   Then make sure we have a flow-id by generating one if it doesn't exist.
   Note: the keys in the header map are plain strings, while we are keeping keywords in the context."
  [correlation-id-fn]
  (interceptor/before ::request-tracer
    (fn [context]
      (let [correlation-ctx (correlation-id-fn (:request context))]
        (-> context
            (assoc ::ctx correlation-ctx)
            (assoc-in [:request ::ctx] correlation-ctx))))))


(def interceptor (make-interceptor correlation-ctx))


(defn channel? [channel]
  (satisfies? protocols/ReadPort channel))


(defn wrap-handler
  "Wraps request->response and adds current request's context to global dynamic binding"
  [async-ring-handler]
  (interceptor/before ::correlation-ctx-wrapper
    (fn [context]
      (binding [*ctx* (::ctx context)]
        (go-let [request  (:request context)
                 result   (async-ring-handler request)
                 response (if (channel? result) (<? result) result)]
          (if (instance? Throwable response)
            (assoc context ::chain/error response)
            (assoc context :response response)))))))


(defmacro with-context
  "Executes body, making context available via global dynamic var"
  [context & body]
  `(binding [*ctx* (::ctx ~context)]
     ~@body))


(defmacro with-request
  "An alias to `with-context`"
  [request & body]
  `(with-context ~request
     ~@body))
