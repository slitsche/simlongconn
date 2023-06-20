;; # Simulation of effect of connection-life-time on Rabbit throughput
^{:nextjournal.clerk/visibility {:code :hide}}
(ns simlongconn.core
  (:gen-class)
  (:require [clojure.pprint :as p]
            [clojure.core.async :as a :refer [>! <! go go-loop chan timeout alt!]]
            [nextjournal.clerk :as clerk]
            [metrics.core :refer [new-registry]]
            [metrics.counters :refer [counter inc! value]])
  (:import   [javax.imageio ImageIO]
             [java.io File]))

;; ## Problem with small samples
;;
;; Given an NLB balances requests between 2 Clusters.
;; If an app with n pods is deployed and request connections
;; the resulting distribution can be written as a seq of zeros and ones.

^{:nextjournal.clerk/visibility {:result :hide}}
(defn random-seq [n]
  (repeatedly n #(Math/round (rand))))

(random-seq 6)

;; For 6 clients there is for our case only one ideal distribution: (0 0 0 1 1 1)
;; If we want to measure the frequency of the optimal distribution we can take the
;; sum of the distribition as follows:

^{:nextjournal.clerk/visibility {:result :hide}}
(defn get-sum [n]
  (apply + (random-seq n)))

^{:nextjournal.clerk/visibility {:result :hide}}
(defn aggregate [n s]
"If we have x pods how often would we get a balanced or not balanced distribution
 out of s samples"
  (->> (repeatedly s #(get-sum n))
       (group-by #(= % 3))
       (map #(vector (first %) (count (second %))))))

(aggregate 6 1000)

;; In roughly one third of all cases we get an optimal distribution

;; ## Simulate the effect of limited connection life time

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
(defn now []
  (java.time.Instant/now))

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
(defn diff-seconds [a b]
  (.getSeconds (java.time.Duration/between a b)))

;; We have two queues in two clusters with a limited capacity

^{:nextjournal.clerk/visibility {:result :hide}}
(def ARabbit (chan 1000))
^{:nextjournal.clerk/visibility {:result :hide}}
(def BRabbit (chan 1000))

;; We want to measure the backlog in the queues.

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
(defn queue-size [c]
  (.. c buf count))

;; A load balancer is a function with no parameter returning a queue.
;; We have two different ones:
;; The first is the setup for one Rabbit

^{:nextjournal.clerk/visibility {:result :hide}}
(defn static-load-balancer
  "load-balancer with one queue"
  [] {:name :A :channel ARabbit})

;;  This is a LB with random assignment with equal chance of target queues.

^{:nextjournal.clerk/visibility {:result :hide}}
(defn random-load-balancer []
  (if (= 0 (Math/round (rand)))
    {:name :A :channel ARabbit}
    {:name :B :channel BRabbit}))

;; We want to test the effect of different connection lifetimes on the assigment
;; of the LB.  This is called a refresh-strategy.  Calling
;; `conn-refresh-strategy` returns an instance of a refresh-strategy which
;; should be used in an instance of an application. It depends on the LB and the
;; expected lifetime of the connection per instance.  If the life-time is 0, it
;; will refresh the connection.  Otherwise we get a strategy which
;; gets a new connection assigned by the LB.  A strategy is implmented as a
;; function.

;; TODO: add penalty for establishing connection

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


;; ## Our applications

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
                 (alts! [[c :payload] (timeout 5)])
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
                 (alts! [c  (timeout 10)])
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

;; We want to be able deploy applications
(defn deploy [f config count]
  (let [run (atom true)
        {lb :lb life-time :conn-life-time-sec} config]
    {:pods (doall
            (repeatedly count
                        #(f (conn-refresh-strategy lb life-time)
                            run)))
     :running run}))

;; We want access a pair of producing and consuming applications with multiple instances per application

^{:nextjournal.clerk/visibility {:result :hide}}
(defn startup
  "Returns a collection of queues, producers and consumers"
  [{procnt :producer-count
    concnt :consumer-count
    :as config}]
  (let [cs (deploy consumer config concnt)
        ps (deploy producer config procnt)]
    {:queues [ARabbit BRabbit]
     :producers ps
     :consumers cs}))

;; We want to cleanly finish a simulation
(defn terminate [deploy]
  (reset! (:running deploy) false))

^{:nextjournal.clerk/visibility {:result :hide}}
(defn shutdown-apps [deploys]
  (doseq [d deploys]
    (terminate d)
    (Thread/sleep 10))
  #_(terminate collector))

;; The simulation depends on
;; - the load-balancer (static queue or multiple)
;; - the duration of simulation
;; - the connection-lifetime
;; - count of producer pods
;; - count of consumer pods
^{:nextjournal.clerk/visibility {:result :hide}}
(def example-config
  {:lb    random-load-balancer
   :duration-sec 10
   :conn-life-time-sec 0
   :producer-count  3
   :consumer-count  3})

^{:nextjournal.clerk/visibility {:result :hide}}
(defn simulate
  "Returns a list of statistics"
  [{lb :lb  life-time-sec :conn-life-time-sec
    duration-sec :duration-sec :as conf}]
  (let [sim (startup conf)
        stats (collect-stats sim duration-sec)]
    (shutdown-apps [(:producers sim) (:consumers sim)])
    (println "shutdown. Size" (queue-size ARabbit) (queue-size BRabbit))
    stats))

^{:nextjournal.clerk/visibility {:result :hide}}
(def simresult
  (simulate
   {:lb    random-load-balancer
    :duration-sec 10
    :conn-life-time-sec 1
    :producer-count 3
    :consumer-count 3}))

(clerk/table (map #(update % :sampled-at str) simresult ))

;; Prepare the data for plotting the graphs
(def timescale (map #(str (:sampled-at %)) simresult))
;; The size of the queues/brokers
(def qsa (map #(first (:queue-size %)) simresult))
(def qsb (map #(second (:queue-size %)) simresult))
;; (def through-put (map #(apply + (:tasks-consumed %)) simresult))

(def through-put
  (->> (map #(apply + (:tasks-consumed %)) simresult)
       (partition 2 1 )
       (map (juxt second first))
       (map #(apply - %))))

^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/plotly {:data [{:x timescale :y qsb :type :scatter :name "Length B"}
                      {:x timescale :y qsa :type :scatter :name "Length A"}
                      #_{:x timescale :y through-put :type :scatter :name "Throughput"}]
               :layout {:title "Queue size per broker"}})

^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/plotly {:data [{:x timescale :y through-put :type :scatter :name "Throughput"}]
               :layout {:title "Throughput of the system"}})

;; ## Some results

;; collection of graphs from different configs

^{:nextjournal.clerk/visibility {:result :hide}}
{:lb    random-load-balancer
 :duration-sec 180
 :conn-life-time-sec 1
 :producer-count 3
 :consumer-count 3}

^{:nextjournal.clerk/visibility {:code :hide}}
(ImageIO/read (File. "doc/2broker-smallqueue-1sec.png"))

;; We hit the limit of the queue regularly.  Does it influence the throughput?
;; (Same config, increased size of queues)


^{:nextjournal.clerk/visibility {:code :hide}}
(ImageIO/read (File. "doc/2broker-3min-10000queuesize.png"))

;; Limit of the queue does not influence the throughput.
;;  What about the connection lifetime?

^{:nextjournal.clerk/visibility {:result :hide}}
{:lb    random-load-balancer
 :duration-sec 180
 :conn-life-time-sec 5
 :producer-count 3
 :consumer-count 3}

^{:nextjournal.clerk/visibility {:code :hide}}
(ImageIO/read (File. "doc/2broker-5seclife.png"))

;; What happens if we increase the number of pods?

^{:nextjournal.clerk/visibility {:result :hide}}
{:lb    random-load-balancer
 :duration-sec 180
 :conn-life-time-sec 5
 :producer-count 6
 :consumer-count 6}

^{:nextjournal.clerk/visibility {:code :hide}}
(ImageIO/read (File. "doc/2broker-6pods-5sec.png"))

;;  What if we increase the life-time with the 6 pods?

^{:nextjournal.clerk/visibility {:result :hide}}
{:lb    random-load-balancer
:duration-sec 180
:conn-life-time-sec 15
:producer-count 6
:consumer-count 6}

^{:nextjournal.clerk/visibility {:code :hide}}
(ImageIO/read (File. "doc/2broker-15sec-6pods.png"))


(comment

(clerk/table
 (simulate
  {:lb    random-load-balancer
   :duration-sec 10
   :conn-life-time-sec 1
   :producer-count 3
   :consumer-count 3}))

(clerk/table
  (simulate example-config))

(clerk/table
 (simulate
  {:lb    random-load-balancer
   :duration-sec 10
   :conn-life-time-sec 1
   :producer-count 3
   :consumer-count 3}))

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
