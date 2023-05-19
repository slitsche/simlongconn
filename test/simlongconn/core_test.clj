(ns simlongconn.core-test
  (:require [clojure.test :refer :all]
            [simlongconn.core :refer :all]))

;; (deftest a-test
;;   (testing "FIXME, I fail."
;;     (is (= 0 1))))

(deftest a-variance
  (testing "random seq of expected length"
    (is (= 3 (count (random-seq 3)))))
  (testing "Values either 1 or 0"
    (is (every? #(or (= % 1) (= % 0))
                (random-seq 3))))
  #_(testing "A small number of odds are imbalanced."
     (is (= 3 (apply + (random-seq 6))))))


(defn get-sum [n]
  (apply + (random-seq n)))

(defn aggregate [n s]
"If we have x pods how often would we get a balanced or not balanced distribution
 out of s samples"
  (->> (repeatedly s #(get-sum n))
       (group-by #(= % 3))
       (map #(vector (first %) (count (second %))))))


(aggregate 6 1000)

;; as in the law of small numbers:  we cannot expect more than a 3rd happy cases
;; from the persective of a single application
