(ns chisel.async-utils
  (:require [clojure.core.async :as a]))


(defmacro <?
  "Version of <! that throw Exceptions that come out of a channel."
  [c]
  `(let [result# (a/<! ~c)]
     (if (instance? Throwable result#)
       (throw result#)
       result#)))


(defmacro <!!?
  "Version of <!! that throw Exceptions that come out of any of the channels."
  [c]
  `(let [result# (a/<!! ~c)]
     (if (instance? Throwable result#)
       (throw result#)
       result#)))


(defmacro go-try
  "Version of go that will catch all exceptions and return them as a channel result"
  {:style/indent 0}
  [& body]
  `(a/go
     (try
       ~@body
       (catch Throwable e# e#))))


(defmacro thread-try
  "Catches exceptions happening inside body and puts them in a channel as a result"
  {:style/indent 0}
  [& body]
  `(a/thread
    (try
      ~@body
      (catch Throwable e# e#))))


(defmacro go-let
  "Shorthand for (go (let [])"
  {:style/indent 1}
  [bindings & body]
  `(go-try (let ~bindings ~@body)))


(defn <!*
  "Synchronizes over a sequence of channels and
  returns a vector of result from each channel."
  [channels]
  (if (empty? channels)
    (a/go channels)
    (a/map vector channels)))


(defn <!!*
  "Synchronizes over a sequence of channels and
  returns a vector of result from each channel.
  If one of the channels times out, result will be nil."
  [channels timeout]
  (let [[result _] (a/alts!! [(a/timeout timeout)
                              (<!* channels)])]
    result))


(defn with-timeout
  "Wraps channel so it will return either a value or a ::timeout after certain time passes"
  [channel timeout]
  (a/go (let [[result _] (a/alts! [(a/timeout timeout)
                                   channel])]
          (or result ::timeout))))


(defn <some!
  "Gets ready values from list of channels before timeout is reached.
  Even if some channel times out, others will make it to the result."
  [channels timeout]
  (go-let [with-timeouts (map #(with-timeout % timeout) channels)
           result-chan   (a/map vector with-timeouts)]
          (remove #(= % ::timeout) (a/<! result-chan))))


(defn <some!!
  "Gets ready values from list of channels before timeout is reached.
  Even if some channel times out, others will make it to the result."
  [channels timeout]
  (a/<!! (<some! channels timeout)))
