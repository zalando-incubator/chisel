(defproject org.zalando/chisel "0.1.0"
  :description "Collection of awesome practices for fast and lightweight web services"
  :url "https://github.com/zalando/chisel"

  :license {:name "MIT" :url  "http://opensource.org/licenses/MIT"}
  :scm {:name "git" :url "git@github.com:zalando/chisel.git"}

  :dependencies [[org.clojure/clojure "1.9.0" :scope "provided"]
                 [org.clojure/core.async "0.4.500"]
                 [org.clojure/tools.logging "0.4.0"]

                 ;; Web-server
                 ;; Pedestal.Jetty includes Jetty, but an older version
                 ;; with some CVEs, so here we depend on Jetty directly
                 [org.eclipse.jetty/jetty-server       "9.4.19.v20190610"]
                 [org.eclipse.jetty/jetty-servlet      "9.4.19.v20190610"]
                 [org.eclipse.jetty.http2/http2-server "9.4.19.v20190610"]
                 [org.eclipse.jetty/jetty-alpn-server  "9.4.19.v20190610"]
                 [org.eclipse.jetty.alpn/alpn-api      "1.1.3.v20160715"]
                 [io.pedestal/pedestal.service "0.5.5"
                  :exclusions [io.dropwizard.metrics/metrics-core]]
                 [io.pedestal/pedestal.jetty "0.5.5"
                  :exclusions [io.dropwizard.metrics/metrics-core
                               org.eclipse.jetty.http2/http2-server
                               org.eclipse.jetty.alpn/alpn-api
                               org.eclipse.jetty/jetty-alpn-server
                               org.eclipse.jetty.websocket/websocket-api
                               org.eclipse.jetty.websocket/websocket-server
                               org.eclipse.jetty.websocket/websocket-client
                               org.eclipse.jetty.websocket/websocket-servlet]]

                 ;; Infra
                 [clj-http "3.10.0"
                  :exclusions [commons-codec]]              ; http client
                 [org.bovinegenius/exploding-fish "0.3.6"]  ; URL parsing
                 [metrics-clojure "2.10.0"]                 ; Metrics facade
                 [metrics-clojure-ring "2.10.0"]            ; Metrics reporter
                 [diehard "0.7.0"]                          ; Circuit Breaker
                 ]

  :plugins [[lein-cloverage "1.0.10"]
            [lein-set-version "0.4.1"]
            [lein-licenses "0.2.2"]]

  :target-path "target/%s"

  :profiles {:uberjar {:aot :all
                       :omit-source true}
             :dev     {:dependencies [[org.clojure/tools.namespace "0.2.11"]

                                      ;; for testing logging side-effects
                                      [org.slf4j/slf4j-api "1.7.14"]
                                      [com.fzakaria/slf4j-timbre "0.3.8"]
                                      [com.taoensso/timbre "4.10.0"]]
                       :plugins      [[pjstadig/humane-test-output "0.8.2"]
                                      [com.jakemccrary/lein-test-refresh "0.22.0"]]}}

  :pom-addition [:developers
                 [:developer {:id "otann"}
                  [:name "Anton Chebotaev"]
                  [:email "anton.chebotaev@zalando.de"]
                  [:role "Maintainer"]
                  [:timezone "+1"]]
                 [:developer {:id "cogitor"}
                  [:name "Emil Varga"]
                  [:email "emil.varga@zalando.de"]
                  [:role "Maintainer"]
                  [:timezone "+1"]]
                 [:developer {:id "avichalp"}
                  [:name "Avichal Pandey"]
                  [:email "avichal.pandey@zalando.de"]
                  [:role "Maintainer"]
                  [:timezone "+1"]]]

  :deploy-repositories {"releases" {:url "https://oss.sonatype.org/service/local/staging/deploy/maven2/" :creds :gpg}
                        "snapshots" {:url "https://oss.sonatype.org/content/repositories/snapshots/" :creds :gpg}})
