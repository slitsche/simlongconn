(ns simlongconn.core
  (:gen-class)
  (:require [clojure.pprint :as p]
            [clojure.core.async :as a :refer [>! <! >!! <!! go go-loop chan]]
            [nextjournal.clerk :as clerk]
            [metrics.core :refer [new-registry]]
            [metrics.counters :refer [counter inc! value]]))

(defn random-seq [n]
  (repeatedly n #(Math/round (rand))))

(defn now []
  (java.time.Instant/now))

(defn diff-seconds [a b]
  (.getSeconds (java.time.Duration/between a b)))

;; # RabbitMQ instances
;;
;; We have two queues in two clusters with a limited capacity

(def ARabbit (chan 1000))
(def BRabbit (chan 1000))

;; We want to measure the backlog in the queues.

(defn queue-size [c]
  (.. c buf count))

;; A load balancer is a function with no parameter returning a queue.
;; We have two different ones:
;; The first is the setup for one Rabbit

(defn static-load-balancer
  "load-balancer with one queue"
  [] {:name :A :channel ARabbit})

;;  This is a LB with random assignment of target queues.

(defn random-load-balancer []
  (if (= 0 (Math/round (rand)))
    {:name :A :channel ARabbit}
    {:name :B :channel BRabbit}))

;; We want to test the effect of different connection lifetimes on the assigment
;; of the LB.  This is called a refresh-strategy.  It depends on the LB and
;; the expected lifetime.  If the life-time is 0, we do not want to refresh the
;; connection.  Otherwise we get a strategy which gets a new connection assigned
;; by the LB.  A strategy is implmented as a function.

(defn conn-refresh-strategy
  "Returns a fn which accepts a instant and returns a channel (queue).
   If life-time = 0 queue will not be changed."
  [lb-fn life-time]
  (let [conn-start (atom (java.time.Instant/now))
        queue (atom (lb-fn))]
    (fn [inst]
      (let []
        (if (and
             (> life-time 0)
             (> (diff-seconds @conn-start inst)
                life-time)) ;; TODO add some variation to life-time?
          (do
            (reset! conn-start inst)
            (swap! queue (fn [_] (lb-fn))))
           @queue)))))


;; # Our applications

;; a registry for metrics

(def reg (new-registry))

(defn producer
  "Function simulates a application which writes to a queue (a channel).
   The channel should be provided as a function which returns
   a channel depening on the given time.  Produces until the atom `run` is false"
  [c-fn run]
  (let [queue (atom "")]
    {:queue queue
     :producer (go-loop [{name :name c :channel} (c-fn (now))]
                 (reset! queue name)
                 (>! c :payload)
                 (Thread/sleep 5)
                 (let [x @run]
                   (if (true? x)
                     (recur (c-fn (now)))
                     x)))}))


;; still quite similar to producer.
;; may evolve differently
(defn consumer
  [c-fn run]
  (let [cnt (counter reg "task-counter")
        queue (atom "")]
    {:counter cnt
     :queue   queue
     ;; TODO: macro
     :consumer (go-loop [{name :name c :channel} (c-fn (now))]
                 (reset! queue name)
                 (<! c)
                 (inc! cnt)
                 (Thread/sleep 5)
                 (let [x @run]
                   (if (true? x)
                     (recur (c-fn (now)))
                     x)))}))


(defn sample
  [s agg]
  (conj agg
        {:sampled-at (now)
         :queue-size (doall (for [q (:queues s)] (queue-size q)))
         :tasks-consumed (doall (for [c (get-in s [:consumers :pods])] (value (:counter c))))
         :prod-queues (doall (map #(deref (:queue %)) (get-in s [:producers :pods])))
         :cons-queues (doall (map #(deref (:queue %)) (get-in s [:consumers :pods])))
         }))

(defn collect-stats
   "Returns a list of records collected for a the duration d seconds from
  simulation s"
  [s d]
  (let [start (now)]
    (loop [res []]
      (Thread/sleep 500)
      (if (> (diff-seconds start (now)) d)
        res
        (recur (sample s res))))))


(defn deploy [name count f config]
  (let [run (atom true)
        {lb :lb life-time :conn-life-time-sec} config]
    {:name name  ;;;  TODO: get rid of the name, since it is a key of the simulation
     :pods (doall
            (repeatedly count
                        #(f (conn-refresh-strategy lb life-time)
                            run)))
     :running run}))

(defn terminate [deploy]
  (reset! (:running deploy) false))

(defn shutdown-apps [deploys]
  (doseq [d deploys]
    (println "Terminating " (:name d))
    (terminate d)
    (Thread/sleep 10))
  #_(terminate collector))

(defn startup
  "Returns a collection of queues, producers and consumers"
  [config]
  (let [cs (deploy "consumer" 3 consumer config)
        ps (deploy "producer" 3 producer config)]
    {:queues [ARabbit BRabbit]
     :producers ps
     :consumers cs}))

;; simulation depend on
;; the load-balancer (static queue or multiple)
;; the connection-lifetime
;; the duration of simulation
(defn simulate
  "Returns a list of statistics"
  [{lb :lb  life-time-sec :conn-life-time-sec duration-sec :duration-sec :as conf}]
  (let [sim (startup conf)
        stats (collect-stats sim duration-sec)]
    (println "Size" (queue-size ARabbit))
    (shutdown-apps [(:producers sim) (:consumers sim)])
    (println "shutdown. Size" (queue-size ARabbit))
    stats))


(def example-config
  {:lb    random-load-balancer
   :duration-sec 10
   :conn-life-time-sec 0})

(clerk/table
  (simulate example-config))

(clerk/table
 (simulate
  {:lb    random-load-balancer
   :duration-sec 10
   :conn-life-time-sec 1}))

(comment

 ;; very long life-time

 (clerk/table
  (simulate random-load-balancer
            10
            10))
(clerk/table
  (simulate static-load-balancer
            10
            5))

 ;;  Use Random Load Balancer

 (clerk/table
  (simulate random-load-balancer
            10
            5))




 (def sim (startup static-load-balancer 0))
 (def sim (startup example-config))

 (shutdown-apps [(:producers sim) (:consumers sim)])

 (def astr (conn-refresh-strategy static-load-balancer 10))

 (astr (now))

 (queue-size ARabbit)

 (def running (atom true))
 (reset! running false)

 (producer astr running)

 (consumer ARabbit running)

 (def cs (simulate static-load-balancer
                   10
                   5))

 )
