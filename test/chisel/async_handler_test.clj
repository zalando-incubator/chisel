(ns chisel.async-handler-test
  (:require [clojure.test :refer :all]
            [chisel.handler :as async]
            [chisel.async-utils :refer [go-let]]
            [chisel.test-utils :as test-utils]
            [clj-http.client :as http-client]
            [clojure.core.async :as a]))


(def port (test-utils/get-free-port))
(def location (str "http://localhost:" port))


(async/def-async good-handler [_request]
                 (a/go {:status 451 :body "sorry"}))


(deftest positive-scenario

  (testing "Status from handler is propagated"
    (test-utils/with-running-server {:port  port
                                     :route good-handler}
      (let [response (http-client/get location {:throw-exceptions false})]
        (is (= 451 (:status response)))
        (is (= "sorry" (:body response)))))))


(async/def-async bad-handler [_request]
                 (go-let [] (throw (ex-info "Unexpected error in the chain" {}))))


(deftest negative-scenario

  (testing "Error thrown is handler does not hang system"

    (test-utils/with-running-server {:port        port
                                     :interceptor bad-handler}
      (let [response (http-client/get location {:throw-exceptions false})]
        (is (= 500 (:status response)))))))
