(ns chisel.metrics
  (:require [io.pedestal.interceptor.helpers :as interceptor]
            [io.pedestal.log :as log]
            [metrics.timers :as timers]
            [metrics.ring.expose :as expose])
  (:import (java.util.concurrent TimeUnit)))


(def metric-registry log/default-recorder)


(def metric-prefix "ingress")


(defn request->string
  "GET /some.service -> zalando.beetroot.api.health.200"
  [context]
  (let [status     (get-in context [:response :status])
        route-name (get-in context [:route :route-name])]
    (str (namespace route-name) "."
         (name route-name) "."
         status)))


(defn make-interceptor [context->sufix]
  (interceptor/around ::metrics
    (fn [context] (assoc context ::request-start (System/currentTimeMillis)))
    (fn [context] (let [start  (get context ::request-start)
                        now    (System/currentTimeMillis)

                        suffix (context->sufix context)
                        timer  (timers/timer metric-registry [metric-prefix suffix])]
                    (.update timer (- now start) (TimeUnit/MILLISECONDS))
                    context))))


(def interceptor (make-interceptor request->string))


(defn handler [request]
  (expose/serve-metrics request metric-registry))
