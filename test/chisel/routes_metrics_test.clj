(ns chisel.routes-metrics-test
  (:require [clojure.test :refer :all]
            [clj-http.client :as http]
            [chisel.metrics :as routes-metrics]
            [taoensso.timbre :as timbre]
            [chisel.test-utils :as test-utils]
            [metrics.core :as metrics]
            [cheshire.core :as json]))

(timbre/set-level! :info)


(def port (test-utils/get-free-port))
(def location (str "http://localhost:" port))


(deftest metrics-test

  (testing "Incoming HTTP request increments the counter"
    (test-utils/with-running-server {:port port :interceptor routes-metrics/interceptor}
      (with-redefs [routes-metrics/metric-registry (metrics/new-registry)]
        (http/get location)
        (let [metrics     (-> (routes-metrics/handler nil)
                              :body
                              (json/parse-string true))
              total-count (-> metrics
                              :ingress.chisel.test-utils.ok.200
                              :rates
                              :total)]
          (is (= 1 total-count)))))))
