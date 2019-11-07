(ns chisel.routes
  "Helpers for building map-based pedestal routes"
  (:require [io.pedestal.interceptor :refer [interceptor]]))


(def noop (interceptor {:enter identity}))


(defn ok [body]
  (interceptor
    {:enter (fn [ctx] (assoc ctx :response {:status 200 :body body}))}))
