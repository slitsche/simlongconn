(ns simlongconn.core
  (:gen-class)
  (:require [clojure.pprint :as p]))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))


(defn random-seq [n]
  (repeatedly n #(Math/round (rand))))

(def RabbitA (atom clojure.lang.PersistentQueue/EMPTY))

(def RabbitB (atom clojure.lang.PersistentQueue/EMPTY))

(defn load-balancer []
  (if (= 0 (Math/round (rand)))
    RabbitA
    RabbitB))

(defn producer [q running]
  (fn []
    (while (true? @running)
      (swap! @q conj :a))))

(defn consumer [q running]
  (fn []
    (while (true? @running)
      (swap! @q pop))))

(defn deploy [f name count]
  (let [run (atom true)
        q   (atom RabbitA #_(load-balancer)) ;; atom in an atom  TODO
        c (f q run)]
    {:name name
     :pods [(future (c))]
     :running run}))

(defn terminate [deploy]
  (reset! (:running deploy) false))

(defn collect-stats []
  (let [acnt (count @RabbitA)
        bcnt (count @RabbitB)]
    (println "A\t" acnt)
    (println "B\t" bcnt)))

(defn collector []
  (let [run (atom true)
        cll (fn []
              (while (true? @run)
                (collect-stats)
                (Thread/sleep 500)))]
    {:running run
     :collect cll}))

(defn shutdown-apps [deploys collector]
  (doseq [d deploys]
    (println "Terminating " (:name d))
    (terminate d)
    (Thread/sleep 10))

  #_(terminate collector))

(defn simulate []
  (let [cs (deploy consumer "consumer" 3)
        ;;ps (deploy producer "producer" 3)
        ]
    (p/pprint cs)
    (Thread/sleep 500)
    (shutdown-apps [cs] nil)
    #_(collect-stats)
    cs))
