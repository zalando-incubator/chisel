(ns chisel.handler
  "Helpers to define request handlers"
  (:require [io.pedestal.interceptor.helpers :as h]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :as a]))


(defmacro def-async
  "Creates a pedestal interceptor from something that looks like a handler function.
  Should return channel with the response inside.

  Example:
  (def-async [request]
    (go {:status 200 :body (:body request)}))

  For intelliJ use 'resolve as defn' to add proper support for highligting"
  [sym-name [request] & body]
  (let [kw-name# (keyword (str *ns*) (name sym-name))]
    `(def ~sym-name
       (h/after ~kw-name#
         (fn [context#]
           (a/go
             (let [~request (:request context#)
                   response# (a/<! (do ~@body))]
               (if (instance? Throwable response#)
                 (assoc context# ::chain/error response#)
                 (assoc context# :response response#)))))))))


(comment

  (def-async handler [request]
    (let [body (:body request)]
      {:status 200 :body body}))


  (macroexpand-1
    '(def-async handler [request]
       (let [body (:body request)]
         {:status 200 :body body})))

  )

