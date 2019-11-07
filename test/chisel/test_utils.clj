(ns chisel.test-utils
  (:require [ring.util.response :as r]
            [io.pedestal.http :as http]
            [chisel.async-utils :as async-utils]
            [clojure.core.async :as a]
            [io.pedestal.interceptor.helpers :as interceptor])
  (:import (java.net ServerSocket)))


(defn get-free-port []
  (let [socket (ServerSocket. 0)]
    (.close socket)
    (.getLocalPort socket)))


(def ok
  (interceptor/handler ::ok
    (fn [_request]
      (r/response "OK"))))


(def noop
  (interceptor/after ::noop
    (fn [ctx] ctx)))


(defn interceptor-routes [interceptor route]

  {"/" {:get          (or route `ok)
        :interceptors (if (sequential? interceptor)
                        interceptor
                        [(or interceptor noop)])}})


(defmacro with-running-server [{:keys [port interceptor route]} & body]
  `(let [route#   (or ~route ok)
         service# {::http/join?  false
                   ::http/port   ~port
                   ::http/routes (interceptor-routes ~interceptor route#)
                   ::http/type   :jetty}
         server#  (-> service#
                      http/create-server
                      http/start)]
     (try
       ~@body
       (finally (http/stop server#)))))


(defn <!!-with-timeout
  "Wraps waiting for a channel data with timeout"
  ([channel] (<!!-with-timeout channel 1000))
  ([channel timeout]
   (a/<!! (async-utils/with-timeout channel timeout))))

(defmacro thrown? [ex & body]
  `(let [result# (try
                   ~@body
                   (catch Throwable e# e#))]
     (if (class? ~ex)
       (= ~ex (class result#))
       (= ~ex result#))))
