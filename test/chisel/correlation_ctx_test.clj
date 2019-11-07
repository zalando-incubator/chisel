(ns chisel.correlation-ctx-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [taoensso.timbre :as timbre]
            [clj-http.client :as http-client]
            [chisel.test-utils :as test-utils]
            [chisel.correlation-ctx :as correlation-ctx]
            [chisel.http-client :as http-async]
            [chisel.handler :as handler]
            [chisel.async-utils :refer [<?]]))

(timbre/set-level! :info)


(def port (test-utils/get-free-port))
(def location (str "http://localhost:" port))
(def dependency-url "http://dependency.invalid")


(handler/def-async async-handler-with-wrapper [request]
  (correlation-ctx/with-request request
    (a/go (<? (http-async/chan :get dependency-url))
          {:status 200 :body "ok"})))


(deftest correlation-headers

  (testing "Correlation id header is captured"
    (let [captured-ctx   (atom nil)
          correlation-id "blueberry pancakes"
          handler        (fn [_req]
                           (reset! captured-ctx correlation-ctx/*ctx*)
                           {:status 200 :body "ok"})]
      (test-utils/with-running-server {:port        port
                                       :route       (correlation-ctx/wrap-handler handler)
                                       :interceptor correlation-ctx/interceptor}
        (http-client/get location {:headers {"X-Flow-Id" correlation-id}})
        (is (= {correlation-ctx/correlation-id-header correlation-id} @captured-ctx)))))


  (testing "When `wrap-handler` is used, correlation ctx is propagated"
    (let [captured-req     (atom nil)
          flow-id          "blubery pancakkes"
          handler          (fn [_req] (a/go (<? (http-async/chan :get dependency-url))
                                            {:status 200 :body "ok"}))
          original-request http-client/request
          wrapped-request  (fn wrapped-request [& [req respond raise]]
                             (if (= (:url req) dependency-url)
                               (do (reset! captured-req req)
                                   (respond {:status 200 :body "{}"}))
                               (original-request req respond raise)))]
      (with-redefs [http-client/request wrapped-request]
        (test-utils/with-running-server {:port        port
                                         :route       (correlation-ctx/wrap-handler handler)
                                         :interceptor correlation-ctx/interceptor}
          (http-client/get location {:headers {"x-flow-id" flow-id}})
          (is (= flow-id (get-in @captured-req [:headers "x-flow-id"])))))))

  (testing "When `with-request` is used, correlation ctx is propagated"
    (let [captured-req     (atom nil)
          flow-id          "blubery pancakkes"
          original-request http-client/request
          wrapped-request  (fn wrapped-request [& [req respond raise]]
                             (if (= (:url req) dependency-url)
                               (do (reset! captured-req req)
                                   (respond {:status 200 :body "{}"}))
                               (original-request req respond raise)))]
      (with-redefs [http-client/request wrapped-request]
        (test-utils/with-running-server {:port        port
                                         :route       `async-handler-with-wrapper
                                         :interceptor correlation-ctx/interceptor}
          (http-client/get location {:headers {"x-flow-id" flow-id}})
          (is (= flow-id (get-in @captured-req [:headers "x-flow-id"]))))))))


(comment

  (run-tests)

  )
