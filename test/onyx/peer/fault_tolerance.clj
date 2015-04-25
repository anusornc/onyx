(ns onyx.peer.fault-tolerance
  (:require [onyx.helper-env :as helper-env :refer [->TestEnv map->TestEnv]]
            [clojure.core.async :refer [chan >!! <!! close! go-loop timeout thread alts!! sliding-buffer]]
            [midje.sweet :refer :all]
            [onyx.peer.task-lifecycle-extensions :as l-ext]
            [onyx.extensions :as extensions]
            [com.stuartsierra.component :as component]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.test-helper :refer [load-config]]
            [onyx.api]))

(def config (load-config))
(def test-env (component/start (->TestEnv (:env-config config) 
                                          (:peer-config config))))
(def batch-size 5)

(def process-middle identity)

(def batch-timeout 500)

(def catalog
  [{:onyx/name :in
    :onyx/ident :core.async/read-from-chan
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/batch-timeout batch-timeout
    :onyx/max-peers 1
    :onyx/batch-size batch-size
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :intermediate-1
    :onyx/fn :onyx.peer.fault-tolerance/process-middle
    :onyx/batch-timeout batch-timeout
    :onyx/type :function
    :onyx/batch-size batch-size}

   {:onyx/name :intermediate-2
    :onyx/fn :onyx.peer.fault-tolerance/process-middle
    :onyx/type :function
    :onyx/batch-timeout batch-timeout
    :onyx/batch-size batch-size}

   {:onyx/name :out
    :onyx/ident :core.async/write-to-chan
    :onyx/type :output
    :onyx/batch-timeout batch-timeout
    :onyx/medium :core.async
    :onyx/batch-size batch-size
    :onyx/doc "Writes segments to a core.async channel"}])

(def workflow [[:in :intermediate-1] 
               [:intermediate-1 :intermediate-2] 
               [:intermediate-2 :out]])

(def chan-size 10000)

(def in-chan (chan (inc chan-size)))

(def out-chan (chan (inc chan-size)))

(defmethod l-ext/inject-lifecycle-resources :in
  [_ _] {:core.async/chan in-chan})

(defmethod l-ext/inject-lifecycle-resources :out
  [_ _] {:core.async/chan out-chan})

(def incomplete 
  (atom #{}))

(def n-messages-total 100000)

(try
  (helper-env/add-peers test-env 9)
  (let [load-data-ch (thread 
                       (loop [ls (repeatedly n-messages-total (fn [] {:id (java.util.UUID/randomUUID)}))]
                         (when-let [segment (first ls)]
                           (swap! incomplete conj segment)
                           (>!! in-chan segment)
                           (recur (rest ls))))
                       (>!! in-chan :done)
                       (close! in-chan))

        job-id (:job-id (helper-env/run-job test-env 
                                            {:catalog catalog
                                             :workflow workflow
                                             :task-scheduler :onyx.task-scheduler/balanced}))
        _ (Thread/sleep 4000)
        task-peers (-> test-env
                       :replica
                       deref
                       :allocations
                       (get job-id))

        task-ids (->> task-peers
                      keys
                      (map (partial extensions/read-chunk (:log (:env test-env)) :task))
                      (map (juxt :name :id))
                      (into {}))

        non-input-task-ids (set (vals (dissoc task-ids :in)))

        remove-completed-ch (go-loop []
                                     (let [v (<!! out-chan)]
                                       (when (and v (not= v :done)) 
                                         (swap! incomplete disj v)
                                         (recur))))
        kill-ch (chan 1)
        ; it can take 3 * batch-timeout to make it through a full circuit, however
        ; there will be circumstances where the peers left alone, so lets try 2* for now
        chaos-kill-ms (* 2 batch-timeout)
        mess-with-peers-ch (go-loop []
                                    (let [[v ch] (alts!! [(timeout chaos-kill-ms) kill-ch])]
                                      (when-not (= ch kill-ch) 
                                        (try 
                                          (when-let [non-input-peers (->> (get (:allocations @(:replica test-env)) job-id)
                                                                          (filter (fn [[task-id peers]]
                                                                                    (and (> (count peers) 1)
                                                                                         (non-input-task-ids task-id))))
                                                                          vals
                                                                          flatten
                                                                          seq)]
                                            (when-let [peer-val (helper-env/lookup-peer test-env (rand-nth non-input-peers))] 
                                              (println "Killing a peer")
                                              (helper-env/remove-peer test-env peer-val)
                                              (helper-env/add-peers test-env 1)))
                                          (catch Exception e
                                            (println "FAILURE MANAGING: " e)))
                                        (recur))))]

    (<!! remove-completed-ch)
    (fact @incomplete => #{})
    (let [after-done (loop [after-done #{}]
                       (let [[v ch] (alts!! [out-chan (timeout 10000)])]
                         (if v 
                           (recur (conj after-done v))
                           after-done)))]
      (fact after-done => #{}))

    (close! kill-ch)
    (<!! mess-with-peers-ch)
    (helper-env/remove-n-peers test-env 9))

  (finally 
    (component/stop test-env)))
