# Chisel

<img width="30%"
     max-height="100px"
     align="right" padding="10px"
     alt="captain's wheel"
     src="/img/chisel.jpg"/>

A library with a set of tools for building readable, fast and lightweight web services

It is designed with specific goals in mind:

- Code should **tell a story** of the implemented features
- App should act **responsibly** to external services
- App should be **scalable** based on the load

This is achieved by a set of practices:

- Build on top of an async web server
- Use an async http client to communicate with external services
- Use circuit-breakers for external communication
- Collect metrics for both ingress and egress http calls
- Enable OpenTracing for both ingress (server span) and egress (client span) calls
- Use global correlation id for groupping logs of single request from unrelated components
- Offer easy integration with existing ring handlers (inc. [swagger1st][swagger-first])
- Do not bloat application logic with irrelevant side-effective features (i.e. correlate logs to see request metadata)

To follow those practices, following choice were made:

- [`pedestal`][pedestal] is chosen as a web service for dead simple architecture and async support
- [`core.async`][core-async] is chosen as core async abstraction, because it's already supported by pedestal
- [`diehard`][diehard] (wrapper around [`failsafe`][failsafe]) for async circuit-breakers
- [`metrics-clojure`][metrics-clojure] (wrapper around [`dropwizard metrics`][dropwizard]) for collecting 
  and exposing metrics

If you need a good async cache to match the needs of an async web server, we recommend the [zapas](https://github.bus.zalan.do/mask/zapas) async cache library.

## Installation

Add `[zalando/chisel "0.1.0"]` to the dependency section in your project.clj file.

## Quick Intro to [Pedestal][pedestal]

Don't worry, if you are new to Pedestal, it's a dead simple framework 
with just three easy concepts, that are utilised in this library:  

- Each HTTP communication is represented with a **[context map][context-map]** (with `request` and `response` keys).
- Context are processed by **[Interceptors][interceptors]** which are similar to middleware, but operate on 
  request and response separately. Each context is processed by a list of interceptors first 
  chaining `enter` functions and then `leave` functions.
- Those functions may return a channel that will eventually return a context, parking current execution and freeing 
  executing thread to process other requests. Once context will be delivered to the channel, interceptor chain will 
  continue to execute.

<img width="50%" align="middle"
     alt="interceptor chain"
     src="/img/interceptor-stack.png"/>

Ring handler could be used as a last interceptor, producing initial response

Here is an example of how you define routes in Pedestal:

```clojure
(ns some.app.service
  (:require [io.pedestal.http :as http]
            [chisel.metrics :as metrics]
            [chisel.trace :as trace]
            [chisel.correlation-ctx :as correlation-ctx]
            [chisel.access-logs :as access-logs]))

(defn api-home [request]
  {:status 200 :body "ok"})

(def routes
  {"/" {:get       `api-home
        "/metrics" {:get `metrics/handler}
        "/api"     {:any          `api/swagger-async
                    :interceptors [access-logs/interceptor
                                   http-metrics/interceptor
                                   correlation-ctx/interceptor
                                   trace/tracing-interceptor
                                   trace/tracing-ctx-interceptor]}}})
```

And then start the server:

```clojure
(ns some.app.http-server
  (:require [io.pedestal.http :as http]
            [some.app.service :as service]))
            
(def service
  {::http/port   (cfg/get :http-port)
   ::http/routes (route/expand-routes routes)
   ::http/type   :jetty})            

;; could be part of state-management library like mount or component
(defn start []
  (-> service/service
      http/default-interceptors
      http/create-server
      http/start))
```


## Logging

Chisel provides a drop-in replacement for pedestal logging and serialises everything passed 
to log functions as json.

```clojure
(require '[chisel.logging :as log])

(log/info :msg "Detected AB test" :test-id test-id :selected-variant variant)
```

Your correlation context will be added to the log under `:context` keyword.
If you'd like to transform the context before logging to obfuscate or hide sensitive information:

```clojure
(log/set-context-filter! (fn [ctx] (dissoc ctx :customer-number)))
```

**NB:** A context filter would be applied only if a custom correlation context is set.

## OpenTrace integration

Pedestal comes with [OpenTracing integration](https://github.com/pedestal/pedestal/blob/master/interceptor/src/io/pedestal/interceptor/trace.clj) already.
Chisel is extending this support with both allowing to extend the server spans with correlation context,
and adding client spans on the http client calls.

First you would need to have tracing backend and register an object implementing `io.opentracing.Tracer` interface. 
For example, if you use [Lightstep](https://lightstep.com/), the code would look like this: 

```clojure
(require '[io.pedestal.log :as log])
(import (com.lightstep.tracer.shared Options$OptionsBuilder)
        (com.lightstep.tracer.jre JRETracer)
        (io.opentracing.noop NoopScopeManager))

(-> (new Options$OptionsBuilder)
    (.withComponentName "service-name")
    (.withAccessToken "access-token")
    (.withClockSkewCorrection false)
    (.withCollectorHost "tracing.example.com")
    (.withCollectorPort 8444)
    (.withScopeManager NoopScopeManager/INSTANCE)
    .build
    (JRETracer.)
    plog/-register)
 ```

The next step is to add the tracing interceptors to your routes:

```clojure
(require '[chisel.trace :as trace])

(def routes
  {"/" {"/api" {:any          `api/handler
                :interceptors [trace/tracing-interceptor
                               trace/tracing-ctx-interceptor]}}})
```

Finally, you need to wrap your handler code with `trace/with-request`, for instance:

```clojure
(require '[chisel.trace :as trace])

(defn handler [request]
  (trace/with-request request
    ;; some request logic
    ))
```

Additionally, you could create new custom spans by wrapping your code with `with-span`:
```clojure
(require '[chisel.trace :as trace])

(trace/with-span "my-custom-span"
  (let [result (some-calculation)]
    (another-calculation result)))
```

Or in case of asynchronous executions, use `go-with-span`:
```clojure
(require '[chisel.trace :as trace]
         '[chisel.async-utils :as async]
         '[chisel.http-client :as http])

(trace/go-with-span "my-async-span"
  (let [result (async/<? (http/chan :get "http://external.api/resource"))]
    (another-calculation result)))
```

You don't necessarily need to add custom spans for your own service logic, but adding the `with-span` wrapper 
will allow the http-client calls to add client spans for each request.

## Asynchronous request processing

Out of the box, Pedestal support async interceptors. So if your API depends on IO or other service, 
consider returning a channel in your interceptor.

However handlers (request->response functions) can't return a channel that easily, because Pedestal does not support it.
For that case you can use `def-async` macro instead of defining your handlers as functions with `defn`:

```clojure
(ns some.app.service
  (:require [clojure.core.async :as a]
            [chisel.async-handler :as handler]))

;; handler must return a channel
(handler/def-async home [request]
  (a/go {:status 451 :body "sorry"}))

;; you can use destructuring, as you'd do in function
(handler/def-async echo [{:keys [body]}]
  (a/go {:status 200 :body body}))
```

This library also provides you with extra utilities to handle errors in async handlers more comfortable:
 
### `go-let` and `go-try` blocks

These two macros will catch any exception inside and return is as a result from the channel:

```clojure
(go-try (throw (ex-info "This will get caught and returned" {})))

;; this is simply a combination of (go-try (let [...] ...))
(go-let [result (/ 1 0)]
  {:status 200
   :body   result})
```

### Trowing reads `<?`

If you are going to wait for some async results inside those blocks, you'd probably want
to detect erros comming from channels and rethrow them:

```clojure
(go-let [amount (account-client/get-shares user-id)
         price  (<? (throw (ex-info "price service unavailable" {})))]
  {:status 200
   :body   {:money (* amount price)}})
```

By combining throwing reads with catching blocks, you can simply throw an exception anywhere in you code -
and it will be propagated to the caller even if it happened deep in the async stack.

## Request Correlation 

Request context is extremely helpful for debugging, especially for tracing problematic requests through
multiple services. All you need is capture an unique identifier and use it in all logging events to
gorup them together to identify where problem ocurred and what was the request context. 

The default settings read, log and propagate the `x-flow-id` header.

If header was not set, new value will be created using [FlowIDGenerator](https://github.com/zalando/tracer#generators)

Add `chisel.correlation-ctx/interceptor` to your list of interceptors and wrap your handler 
with `correlation-ctx/wrap-handler` a middleware that enables dynamic binding to capture context of each request:

```clojure
(def ctx-handler (correlation-ctx/wrap-handler request->response))
```

Alternatively you could wrap your handler or an interceptor login in a `with-request` macro:

```clojure
(defn handler [request]
  (correlation-id/with-request request
    ;; request logic
    ))
    
(def interceptor
  (interceptor/before ::name
    (fn [context]
      (with-context context
        ;; interceptor
        ))))    
``` 

The extracted request context will be available in global `correlation-ctx/*ctx*` var.

An example with swagger1st:

```clojure
(def swagger-handler
  (-> (s1st/context :yaml-cp "swagger.yaml")
      (s1st/discoverer :definition-path "/swagger.json" :ui-path "/ui/")
      (s1st/mapper)
      (s1st/parser)
      (s1st/executor :resolver resolve-operation)))

(def swagger-async (correlation-ctx/wrap-handler swagger-handler))

(def routes
  {"/" {"/api" {:any          `swagger-async
                :interceptors [correlation-ctx/interceptor]}}})
```

### Custom context 

If you wish to alter how context is extracted, you can provide your own function 
and make an interceptor that will use it:

```clojure
(defn request->ctx [{:keys [headers]}]
  (let [default-ctx (correlation-ctx/request->ctx request)
        extra-ctx   {:x-platform    (get headers "x-device-platform")
                     :x-app-version (get headers "x-app-version")}]
    (into default-ctx extra-ctx)))

(def routes
  {"/" {"/api" {:any          `swagger-async
                :interceptors [(request-ctx/make-interceptor request->ctx)]}}})
``` 

Keep in mind, that this context will be used directly as extra headers added to each http call.

## Enhanced HTTP client

Chisel provides a wrapper around [`clj-http`](https://github.com/dakrone/clj-http) async API with extra features:

```clojure
(require '[chisel.http-client :as http])

(http/async :get "http://external.api/resource"
            (fn on-success [response])
            (fn on-failure [exception]))
```

**NB:** In current version of `clj-http` async calls CAN NOT specify connection manager.

Two wrappers exists for two common ways of dealing with async primitives:

```clojure
;; with promises
(let [response @(http/promise :get "http://external.api/resource")]
  (println "got response" response))
  
;; and with channels
(go-let [response (<? (http/chan :get "http://external.api/resource"))]
  (println "got response" response))  
``` 

### Correlation context propagation

If you've used and configured the `correlation-ctx` interceptor, then correlation context extracted from 
the request will be propagated to the remote service in the form of headers automatically.

### Metric collection

It is highly advisable to provide `:route-name`, which will be used for logs and metrics 
for each request to improve visibility and traceability of your system. If not provided, domain name is used.

Namespaced keyword, like `:service/method` or `::method` could be used.

For each request latency is measured and is collected with status to a metric registry.

If a GET request to http://example.com was succesfull, then name `egress.example.com.200` will be used.

You can use `chisel.metrics/handler` to aggregate data in the json form.

### Circuit breaker

If your service is a part of the army of microservices, then you may want to use flood protection for an army of 
cascading errors, which is provided by [circuit breakers](https://martinfowler.com/bliki/CircuitBreaker.html).

```clojure
(require '[diehard.core :as dh])

;; if 8 out of 10 would be unsuccessful, circuit will open
(dh/defcircuitbreaker customer-number-breaker
  {:failure-threshold-ratio [8 10]
   :delay-ms                1000})
   
(defn customer-number [uuid]
  (go-let [url    (str base-url "/customer-numbers/" uuid)
           opts   {:query-params    {:uuid uuid}
                   :circuit-breaker customer-number-breaker}
           result (http/async-chan :get url opts)
           {:keys [status body]} (<? result)]
    (cond
      (= status 200) (:customer_number (json/parse-string body true))
      (= status 404) nil     
      :else (log/error "Unable to get customer number for:" uuid request-ctx/*ctx*))))   
```

### Retries & circuit-breaker

You can provide `:retries` (0 by default) as an additional option to retry before calling `on-failure`.
If there are retries left, circuit breaker won't be notified about this nuisance.

Exact behaviour would depend on how you decide to treat responses. 
By default [`clj-http`](https://github.com/dakrone/clj-http#exceptions) throws an exception 
in all statuses except `#{200 201 202 203 204 205 206 207 300 301 302 303 307}` which will trigger failure callback.

You can alter this behaviour by providing `{:throw-exceptions false}` option to treat all responses as successful 
and only get a network errors as exceptions. 

If you want to have a more granular control of what statuses are exceptional, use `:unexceptional-status` from
[`clj-http`](https://github.com/dakrone/clj-http#exceptions)  with a predicate to express what you consider 
a failure and want to tip the circuit breaker.

```clojure
(http/async-chan :get url {:unexceptional-status #(<= 200 % 299)})
```

## API routes Metrics

To help you monitor health of your application, the latencies of all the incoming 
requests are measured and reported to a central metric registry, grouped by statuses.

By default pedestal route names would be used for metric names. If you have your routes 
definen like this:

```clojure
(ns some.app.service
  (:require [io.pedestal.http :as http]
            [some.app.api :as api]
            [chisel.metrics :as metrics]))

(def routes
  {"/" {:get          `api/home
        :interceptors [metrics/interceptor]}})
```

Where `api/home` is a request to response function, then 
metric name would be `ingress.some.app.api.home.200` for successful response.

Use following interceptors to aggregate and publish metrics:

```clojure
(ns some.app.service
  (:require [chisel.metrics :as metrics]))


(def routes
  {"/" {:interceptors [metrics/interceptor]
        "/metrics"    {:get `metrics/handler}}})
```

## API logging

With `chisel.access-logs/interceptor` you can enable logging of all incoming requests,
their statuses and durations.

## License

The MIT License (MIT) Copyright © [2019] Zalando SE, https://tech.zalando.com

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and 
associated documentation files (the “Software”), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell 
copies of the Software, and to permit persons to whom the Software is furnished to do so, 
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, 
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR 
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

[pedestal]: http://pedestal.io/
[core-async]: https://github.com/clojure/core.async
[diehard]: https://github.com/sunng87/diehard
[failsafe]: https://github.com/jhalterman/failsafe
[metrics-clojure]: https://github.com/sjl/metrics-clojure
[dropwizard]: http://metrics.dropwizard.io/4.0.0/
[swagger-first]: https://github.com/zalando-stups/swagger1st
[context-map]: (http://pedestal.io/reference/context-map)
[interceptors]: (http://pedestal.io/reference/interceptors)
