(ns chisel.access-logs
  "Provides access logs to all incomming requests"
  (:require [chisel.logging :as log]
            [chisel.correlation-ctx :as correlation-id]
            [io.pedestal.interceptor.helpers :as interceptor]))


(def interceptor
  (interceptor/around ::interceptor
    (fn [context] (assoc context ::request-start (System/currentTimeMillis)))
    (fn [context] (let [{:keys [request response]} context
                        start    (get context ::request-start)
                        now      (System/currentTimeMillis)
                        duration (- now start)]
                    (correlation-id/with-context context
                      (log/info :msg "Incoming request"
                                :route (-> context :route :route-name)
                                :status (:status response)
                                :duration duration
                                :method (:request-method request)
                                :uri (:uri request)
                                :query (:query-string request)))
                    context))))
