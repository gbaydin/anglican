;;; distributions.clj -- A common distribution protocol with several implementations.

;; by Mark Fredrickson http://www.markmfredrickson.com
;; May 10, 2010
;; Changes added by William Leung
;; Jun 24, 2010

;; Copyright (c) Mark M. Fredrickson, 2010. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.htincanter.at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.

;; Stripped down and modified by David Tolpin for use with embang,
;; http://bitbucket.org/dtolpin/embang, and up-to-date version of colt.

(ns ^{:doc "Probability functions (pdf, cdf, draw, etc.) for common distributions,
           and for collections, sets, and maps."
      :author "Mark M. Fredrickson and William Leung"}
  embang.colt.distributions
  (:import java.util.Random
           (cern.jet.random Beta Binomial ChiSquare Uniform Exponential
                            Gamma NegativeBinomial Normal Poisson StudentT)
           (cern.jet.stat Probability)
           (cern.jet.random.engine MersenneTwister))
  (:use [clojure.set :only (intersection difference)]
        [clojure.math.combinatorics :only (combinations)]))

(defprotocol Distribution
  "
  The distribution protocol defines operations on probability distributions.
  Distributions may be univariate (defined over scalars) or multivariate
  (defined over vectors). Distributions may be discrete or continuous.

  For a list of types that implement the protocol run (extenders Distribution).
  Implementations are provided for the various Clojure collection datatypes.
  See the example below for using the distribution methods on these types.

  See also:
    pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Probability_distribution

  Examples:
    (support [1 3 4 2 1 3 4 2]) ; returns the set #{1 2 3 4}
    (draw [1 3 4 2 1 3 4 2]) ; returns a value from #{1 2 3 4}
    (pdf [2 1 2] 1) ; returns the value 1/3
  (cdf [2 1 2 3] 2) ; returns the value 3/4
  "
  (pdf [d v]
    "
    A function of the incanter.distribution.Distribution protocol.

    Returns the value of the probability density/mass function for the
    distribution d at support v.

    See also:
      Distribution, cdf, draw, support

    References:
      http://en.wikipedia.org/wiki/Probability_density_function

    Examples:
      (pdf [2 1 2] 1) ; returns the value 1/3\n")

  (cdf [d v]
    "
    A function of the incanter.distribution.Distribution protocol.

    Returns the value of the cumulative density function for the
    distribution d at support v.

    See also:
      Distribution, pdf, draw, support

    References:
      http://en.wikipedia.org/wiki/Cumulative_distribution_function

    Examples:
      (cdf [2 1 2 3] 2) ; returns the value 3/4 \n")

  (draw [d]
    "
    A function of the incanter.distribution.Distribution protocol.

    Returns a randomly drawn value from the support of distribution d.

    See also:
      Distribution, pdf, cdf, support

    Examples:
      (draw [1 3 4 2 1 3 4 2]) ; returns a value from #{1 2 3 4}\n")

  (support [d]

    "
    **** EXPERIMENTAL ****
    A function of the incanter.distribution.Distribution protocol.

    Returns the support of the probability distribution d.
    For discrete distributions, the support is a set (i.e. #{1 2 3}).
    For continuous distributions, the support is a 2 element vector
    describing the range. For example, the uniform distribution over
    the unit interval would return the vector [0 1].

    This function is marked as experimental to note that the output
    format might need to adapt to more complex support structures.
    For example, what would best describe a mixture of continuous
    distributions?

    See also:
      Distribution, pdf, draw, support

    References:
      http://en.wikipedia.org/wiki/Cumulative_distribution_function

    Examples:
      (cdf [2 1 2 3] 2) ; returns the value 3/4 \n")
  (mean [d] "mean")
  (variance [d] "variance"))

(defn- tabulate
  "Private tabulation function that works on any data type, not just numerical"
  [v]
  (let [f (frequencies v)
        total (reduce + (vals f))]
    (into {} (map (fn [[k v]] [k (/ v total)]) f))))

(defn- simple-cdf
  "
  Compute the CDF at a value by getting the support and adding up the values until
  you get to v (inclusive)
  "
  [d v]
  (reduce + (map #(pdf d %) (filter #(>= v %) (support d)))))

;; Extending all sequence types to be distributions
(extend-type clojure.lang.Sequential
  Distribution
  (pdf [d v] (get (tabulate d) v 0))
  (cdf [d v] (simple-cdf d v)) ; TODO check comparable elements
  (draw [d] (nth d (rand-int (count d))))
                                        ; (draw [d n] (repeatedly n #(draw d)))
  (support [d] (set d))
  (mean [d] (if (or (empty? d)
                    (not (every? #(isa? (class %) java.lang.Number) d)))
              nil
              (/ (reduce + d)
                 (count d))))
  (variance [d] (if (or (empty? d)
                        (not (every? #(isa? (class %) java.lang.Number) d)))
                  nil
                  (let [mu (mean d)]
                    (/ (reduce + (map #(* (- % mu) (- % mu)) d))
                       (count d))))))

;; Sets (e.g. #{1 2 3}) are not seqs, so they need their own implementation
(extend-type clojure.lang.APersistentSet
  Distribution
  (pdf [d v] (if (get d v) (/ 1 (count d)) 0))
  (cdf [d v] (if-not (isa? (class d) clojure.lang.PersistentTreeSet)
               nil
               (cdf (vec d) v)))
  (draw [d] (nth (support d) (rand-int (count d))))
  (support [d] d)
  (mean [d] (if (or (empty? d)
                    (not (every? #(isa? (class %) java.lang.Number) d)))
              nil
              (/ (reduce + d)
                 (count d))))
  (variance [d] (if (or (empty? d)
                        (not (every? #(isa? (class %) java.lang.Number) d)))
                  nil
                  (let [mu (mean d)]
                    (/ (reduce + (map #(* (- % mu) (- % mu)) d))
                       (count d))))))

(defn- take-to-first
  "
  Returns a lazy sequence of successive items from coll up to
  and including the point at which it (pred item) returns true.
  pred must be free of side-effects.

  src: http://www.mail-archive.com/clojure@googlegroups.com/msg25706.html
  "
  [pred coll]
  (lazy-seq
   (when-let [s (seq coll)]
     (if-not (pred (first s))
       (cons (first s) (take-to-first pred (rest s)))
       (list (first s))))))

(defn roulette-wheel
  "Perform a roulette wheel selection given a list of frequencies"
  [freqs]
  (let [nfreqs (count freqs)
        tot (reduce + freqs)]
    (if (= tot 0)
      nil
      (let [dist (map #(/ % tot) freqs)
            rval (double (rand))]
        (loop [acc 0, i 0]
          (let [lb acc, ub (+ acc (nth dist i))]
            (cond (>= (+ i 1) nfreqs) i
                  (and (>= rval lb) (< rval ub)) i
                  :else (recur ub (+ i 1)))))))))

;; map extension takes values as frequencies
(extend-type clojure.lang.APersistentMap
  Distribution
  (pdf [d v] (if-not (contains? d v)
               0
               (/ (get d v) (reduce + (vals d)))))
  (cdf [d v] (if-not (isa? (class d) clojure.lang.PersistentTreeMap)
               nil
               (let [nd (count (support d))
                     compd (.comparator d)
                     fkey (first (keys d))]
                 (cond (= nd 0) 0
                       (= nd 1) (if (= -1 (.compare compd v fkey)) 0 1)
                       :else (let [upto (take-to-first #(= (key %) v) d)]
                               (if-not (contains? d v)
                                 (if (= -1 (.compare compd v fkey)) 0 1)
                                 (/ (reduce + (vals upto))
                                    (reduce + (vals d)))))))))
  (draw [d] (nth (keys d) (roulette-wheel (vals d))))
  (support [d] (keys d))
  (mean [d] (if (empty? d)
              nil
              (let [vs (vals d)]
                (if-not (every? #(isa? (class %) java.lang.Number) vs)
                  nil
                  (/ (reduce + vs)
                     (count d))))))
  (variance [d] (if (empty? d)
                  nil
                  (let [vs (vals d)]
                    (if-not (every? #(isa? (class %) java.lang.Number) vs)
                      nil
                      (let [mu (mean d)]
                        (/ (reduce + (map #(* (- (val %) mu) (- (val %) mu)) d))
                           (count d))))))))

(defrecord UniformInt [start end]
  Distribution
  (pdf [d v] (/ 1 (- end start)))
  (cdf [d v] (* v (pdf d v)))
  (draw [d]
    ;; for simplicity, cast to BigInt to use the random bitstream;
    ;; a better implementation would handle different types differently
    (let [r (bigint (- end start))
          f #(+ start (BigInteger. (.bitLength r) (Random.)))]
      (loop [candidate (f)]        ; rejection sampler, P(accept) > .5
        (if (< candidate end) candidate (recur (f))))))
  (support [d] (range start end))
  (mean [d] (/ (reduce + (support d))
               (- end start)))
  (variance [d] (let [vals (support d)
                      mu (mean vals)]
                  (/ (reduce + (map #(* (- % mu) (- % mu)) vals))
                     (- end start)))))

(defn integer-distribution
  "
  Create a uniform distribution over a set of integers over
  the (start, end] interval. An alternative method of creating
  a distribution would be to just use a sequence of integers
  (e.g. (draw (range 100000))). For large sequences, like the one
  in the example, using a sequence will be require realizing the
  entire sequence before a draw can be taken. This less efficient than
  computing random draws based on the end points of the distribution.

  Arguments:
  start The lowest end of the interval, such that (>= (draw d) start)
        is always true. (Default 0)
    end The value at the upper end of the interval, such that
        (> end (draw d)) is always true. Note the strict inequality.
        (Default 1)

  See also:
    pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Uniform_distribution_(discrete)

  Examples:
    (pdf (integer-distribution 0 10) 3) ; returns 1/10 for any value
    (draw (integer-distribution -5 5))
    (draw (integer-distribution (bit-shift-left 2 1000))) ; probably a very large value
  "
  ([] (integer-distribution 0 1))
  ([end] (integer-distribution 0 end))
  ([start end]
     {:pre [(> end start)]}
     (UniformInt. start end)))

;;;; Combination Sampling: Draws from the nCk possible combinations ;;;;

(defn- nCk [n k]
  (cond
   (or (< n 0) (< k 0) (< n k)) 0
   (or (= k 0) (= n k)) 1
   :else (/ (reduce * 1 (range (inc (- n k)) (inc n))) (reduce * 1 (range 1 (inc k))))))

(defn- decode-combinadic
  "
  Decodes a 0 to nCk - 1 integer into its combinadic form, a set of
  k-tuple of indices, where each index i is 0 < i < n - 1
  "
  [n k c]
  {:pre [(<= 0 c) (> (nCk n k) c)] }
  (loop [candidate (dec n) ks (range k 0 -1) remaining c tuple '()]
    (if (empty? ks) tuple ;; <- return value of function
        (let [k (first ks)
              v (first (filter #(>= remaining (nCk % k)) (range candidate (- k 2) -1)))]
          (assert (not (nil? v)))
          (recur v (rest ks) (- remaining (nCk v k)) (conj tuple v))))))



(defn- res-sampler
  "
  Get a sample from the nCk possible combinations. Uses a reservoir
  sample from Chapter 4 of Tille, Y. (2006). Sampling Algorithms. Springer, New York.
  "
  [n k]
  (let [res (transient (into [] (range 0 k)))]
    (dorun (map
            (fn [i] (if (< (/ k i) (rand)) (assoc! res (rand-int k) i)))
            (range k n)))
    (persistent! res)))

(defrecord Combination [n k u]
  Distribution
  (pdf [d v] (/ 1 (nCk n k)))
  (cdf [d v] nil)            ; TODO: this requires encoding combinations
  (draw [d] (res-sampler n k))
  (support [d] (map #(decode-combinadic n k %) (range 0 (nCk n k))))
  (mean [d] nil)
  (variance [d] nil))

(defn combination-distribution
  "
  Create a distribution of all the k-sized combinations of n integers.
  Can be considered a multivariate distribution over k-dimensions, where
  each dimension is a discrete random variable on the (0, n] range (though
  these variables are decidedly non-independent).

  A draw from this distribution can also be considered a sample without
  replacement from any finite set, where the values in the returned
  vector represent the indices of the items in the set.

  Arguments:
    n     The number of possible items from which to select.
    k     The size of a sample (without replacement) to draw.

  See also:
    test-statistic-distribution, integer-distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Combination

  "
  [n k]
  {:pre [(>= n k) (and (<= 0 n) (<= 0 k))] }
  (Combination. n k (integer-distribution 0 (nCk n k))))

(def ^:dynamic *test-statistic-iterations* 1000)
(def ^:dynamic *test-statistic-map* pmap)

(defn test-statistic-distribution
  "
  Create a distribution of the test-statistic over the possible
  random samples of treatment units from the possible units.

  There are two methods for generating the distribution. The
  first method is enumerating all possible randomizations and
  performing the test statistic on each. This gives the exact
  distribution, but is only feasible for small problems.

  The second method uses a combination-distribution to sample
  for the space of possible treatment assignments and applies
  the test statistic the sampled randomizations. While the
  resulting distribution is not exact, it is tractable for
  larger problems.

  The algorithm automatically chooses between the two methods
  by computing the number of possible randomizations and
  comparing it to *test-statistic-iterations*. If the exact
  distribution requires fewer than *test-statistic-iterations*
  the enumeration method is used. Otherwise, it draws
  *test-statistic-iterations* total samples for the simulated
  method.

  By default, the algorithm uses parallel computation. This is
  controlled by the function *test-statistic-map*, which is
  bound to pmap by default. Bind it to map to use a single
  thread for computation.

  Arguments:
    test-statistic      A function that takes two vectors and summarizes
        the difference between them
    n     The number of total units in the pool
    k     The number of treatment units per sample

  See also:
    combination-distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Sampling_distribution
    http://en.wikipedia.org/wiki/Exact_test
    http://en.wikipedia.org/wiki/Randomization_test
    http://en.wikipedia.org/wiki/Lady_tasting_tea

  "
  [test-statistic n k]
  ;; for now returns entire set of computed values, should summarize via frequencies
  (*test-statistic-map* test-statistic ; *t-s-m* is bound to pmap by default
                        (let [cd (combination-distribution n k)]
                          (if (> (nCk n k) *test-statistic-iterations*)
                                        ; simulated method
                            (repeatedly *test-statistic-iterations* #(draw cd))
                                        ; exact method
                            (combinations (range 0 n) k)))))

(def inf+ Double/POSITIVE_INFINITY)
(def inf- Double/NEGATIVE_INFINITY)

(defrecord Normal-rec [mean sd]
  Distribution
  (pdf [d v] (.pdf (Normal. mean sd (MersenneTwister.)) v))
  (cdf [d v] (.cdf (Normal. mean sd (MersenneTwister.)) v))
  (draw [d] (cern.jet.random.Normal/staticNextDouble mean sd))
  (support [d] [inf-, inf+])
  (mean [d] mean)
  (variance [d] (* sd sd)))

(defn normal-distribution
  "
  Returns a Normal distribution that implements the
  Distribution protocol.

  Arguments:
    mean  The mean of the distribution. One of two parameters
          that summarize the Normal distribution (default 0).
    sd    The standard deviation of the distribution.
          The second parameter that describes the Normal (default 1).

  See also:
      Distribution, pdf, cdf, draw, support

  References:
      http://en.wikipedia.org/wiki/Normal_distribution

  Example:
      (pdf (normal-distribution -2 (sqrt 0.5)) 1.96)
  "
  ([] (normal-distribution 0 1))
  ([mean sd] (Normal-rec. mean sd)))

(defrecord Beta-rec [alpha beta]
  Distribution
  (pdf [d v] (.pdf (Beta. alpha beta (MersenneTwister.)) v))
  (cdf [d v] (.cdf (Beta. alpha beta (MersenneTwister.)) v))
  (draw [d] (cern.jet.random.Beta/staticNextDouble alpha beta))
  (support [d] [0,1])
  (mean [d] (/ alpha (+ alpha beta)))
  (variance [d] (/ (* alpha beta)
                   (* (+ alpha beta)
                      (+ alpha beta)
                      (+ alpha beta 1)))))
(defn beta-distribution
  "
  Returns a Beta distribution that implements the Distribution protocol.

  Arguments:
    alpha      (default 1)
    beta       (default 1)

  See also:
    Distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Beta_distribution

  Example:
    (pdf (beta-distribution 1 2) 0.5)
  "
  ([] (beta-distribution 1 1))
  ([alpha beta] (Beta-rec. alpha beta)))

(defrecord Binomial-rec [n p]
  Distribution
  (pdf [d v] (.pdf (Binomial. n p (MersenneTwister.)) v))
  (cdf [d v] (.cdf (Binomial. n p (MersenneTwister.)) v))
  (draw [d] (cern.jet.random.Binomial/staticNextInt n p))
  (support [d] (range (+ n 1)))
  (mean [d] (* n p))
  (variance [d] (* n p (- 1 p))))

(defn binomial-distribution
  "
  Returns a Binomial distribution that implements the Distribution protocol.

  Arguments:
    size       (default 1)
    prob       (default 1/2)

  See also:
    Distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Binomial_distribution

  Example:
    (pdf (binomial-distribution 20 1/4) 10)
  "
  ([] (binomial-distribution 1 1/2))
  ([n p] (Binomial-rec. n p)))

(defrecord ChiSquare-rec [df]
  Distribution
  (pdf [d v] (.pdf (ChiSquare. df (MersenneTwister.)) v))
  (cdf [d v] (.cdf (ChiSquare. df (MersenneTwister.)) v))
  (draw [d] (cern.jet.random.ChiSquare/staticNextDouble df))
  (support [d] [0,inf+])
  (mean [d] df)
  (variance [d] (* 2 df)))

(defn chisq-distribution
  "
  Returns a Chi-square distribution that implements the Distribution protocol.

  Arguments:
    df         (default 1)

  See also:
    Distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Chi_square_distribution

  Example:
    (pdf (chisq-distribution 2) 5.0)
  "
  ([] (chisq-distribution 1))
  ([df] (ChiSquare-rec. df)))

(defrecord Exponential-rec [rate]
  Distribution
  (pdf [d v] (.pdf (Exponential. rate (MersenneTwister.)) v))
  (cdf [d v] (.cdf (Exponential. rate (MersenneTwister.)) v))
  (draw [d] (cern.jet.random.Exponential/staticNextDouble rate))
  (support [d] [0,inf+])
  (mean [d] (/ 1 rate))
  (variance [d] (/ 1 (* rate rate))))

(defn exponential-distribution
  "
  Returns a Exponential distribution that implements the Distribution protocol.

  Arguments:
    rate       (default 1)

  See also:
    Distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Exponential_distribution

  Example:
    (pdf (exponential-distribution 1/2) 2.0)
  "
  ([] (exponential-distribution 1))
  ([rate] (Exponential-rec. rate)))

(defrecord Gamma-rec [shape rate]
  Distribution
  (pdf [d v] (.pdf (Gamma. shape rate (MersenneTwister.)) v))
  (cdf [d v] (.cdf (Gamma. shape rate (MersenneTwister.)) v))
  (draw [d] (Gamma/staticNextDouble shape rate))
  (support [d] [0,inf+])
  (mean [d] (/ shape rate))
  (variance [d] (/ shape (* rate rate))))

(defn gamma-distribution
  "
  Returns a Gamma distribution that implements the
  Distribution protocol.

  Arguments:
    shape (α)  (default 1)
    rate  (β)  (default 1)

  See also:
    Distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Gamma_distribution

  Example:
    (pdf (gamma-distribution 1 2) 10)
  "
  ([] (gamma-distribution 1 1))
  ([shape rate] (Gamma-rec. shape rate)))

(defrecord NegativeBinomial-rec [size prob]
  Distribution
  (pdf [d v] (.pdf (NegativeBinomial. size prob (MersenneTwister.)) v))
  (cdf [d v] (.cdf (NegativeBinomial. size prob (MersenneTwister.)) v))
  (draw [d] (cern.jet.random.NegativeBinomial/staticNextInt size prob))
  (support [d] [0,inf+])
  (mean [d] (/ (* size prob)
               (- 1 prob)))
  (variance [d] (/ (* size prob)
                   (* (- 1 prob) (- 1 prob)))))

(defn neg-binomial-distribution
  "
  Returns a Negative binomial distribution that implements the Distribution protocol.

  Arguments:
    size       (default 10)
    prob       (default 1/2)

  See also:
    Distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Negative_binomial_distribution

  Example:
    (pdf (neg-binomial-distribution 20 1/2) 10)
  "
  ([] (neg-binomial-distribution 10 1/2))
  ([size prob] (NegativeBinomial-rec. size prob)))

(defrecord Poisson-rec [lambda]
  Distribution
  (pdf [d v] (.pdf (Poisson. lambda (MersenneTwister.)) v))
  (cdf [d v] (.cdf (Poisson. lambda (MersenneTwister.)) v))
  (draw [d] (cern.jet.random.Poisson/staticNextInt lambda))
  (support [d] [0,inf+])
  (mean [d] lambda)
  (variance [d] lambda))

(defn poisson-distribution
  "
  Returns a Poisson distribution that implements the Distribution protocol.

  Arguments:
    lambda     (default 1)

  See also:
    Distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Poisson_distribution

  Example:
    (pdf (poisson-distribution 10) 5)
  "
  ([] (poisson-distribution 1))
  ([lambda] (Poisson-rec. lambda)))

(defrecord StudentT-rec [df]
  Distribution
  (pdf [d v] (.pdf (StudentT. df (MersenneTwister.)) v))
  (cdf [d v] (.cdf (StudentT. df (MersenneTwister.)) v))
  (draw [d] (cern.jet.random.StudentT/staticNextDouble df))
  (support [d] [inf-,inf+])
  (mean [d] (if (> df 1) 0 nil))
  (variance [d] (cond (> df 2) (/ df (- df 2))
                      (and (> df 1) (<= df 2)) inf+
                      :else nil)))
(defn t-distribution
  "
  Returns a Student-t distribution that implements the Distribution protocol.

  Arguments:
    df         (default 1)

  See also:
    Distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Student-t_distribution

  Example:
    (pdf (t-distribution 10) 1.2)
  "
  ([] (t-distribution 1))
  ([df] (StudentT-rec. df)))

(defrecord Uniform-rec [min max]
  Distribution
  (pdf [d v] (.pdf (Uniform. min max (MersenneTwister.)) v))
  (cdf [d v] (.cdf (Uniform. min max (MersenneTwister.)) v))
  (draw [d] (cern.jet.random.Uniform/staticNextDoubleFromTo min max))
  (support [d] [min,max])
  (mean [d] (/ (+ min max)
               2.))
  (variance [d] (/ (* (- max min) (- max min))
                   12.)))

(defn uniform-distribution
  "
  Returns a Uniform distribution that implements the Distribution protocol.

  Arguments:
    min        (default 0.)
    max        (default 1.)

  See also:
    Distribution, pdf, cdf, draw, support

  References:
    http://en.wikipedia.org/wiki/Uniform_distribution

  Example:
    (pdf (uniform-distribution 1. 10.) 5)
  "
  ([] (uniform-distribution 0. 1.))
  ([min max] (Uniform-rec. min max)))
