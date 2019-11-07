(ns chisel.access-logs-test
  (:require [clojure.test :refer :all]
            [clj-http.client :as http]
            [taoensso.timbre :as timbre]
            [chisel.access-logs :as access-logs]
            [chisel.test-utils :as test-utils]
            [io.pedestal.log :as pedestal-log]))

(timbre/set-level! :info)


(def port (test-utils/get-free-port))
(def location (str "http://localhost:" port))


(deftest lib-integration

  (testing "When timbre is used as backend, it gets called on http request"
    (let [log-args (atom nil)]
      (with-redefs [timbre/-log! (fn [& args] (reset! log-args args))]
        (test-utils/with-running-server {:port        port
                                         :interceptor access-logs/interceptor}
          (http/get location)
          (is (not (nil? @log-args)))))))

  (testing "Pedestal logging is called with status keyword on http request with info level"
    (let [log-args  (atom nil)
          log-level (atom nil)]
      (with-redefs [pedestal-log/log (fn [keyvals level]
                                       (reset! log-level level)
                                       (reset! log-args keyvals))]
        (test-utils/with-running-server {:port port
                                         :interceptor access-logs/interceptor}
          (http/get location)
          (is (= :info @log-level))
          (is (= 200 (:status @log-args)))
          (is (= "/" (:uri @log-args))))))))
