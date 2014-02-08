(ns onyx.peer.virtual-peer
  (:require [clojure.core.async :refer [chan alts!! >!! <!! close!]]
            [com.stuartsierra.component :as component]
            [dire.core :as dire]
            [onyx.extensions :as extensions]
            [onyx.peer.task-pipeline :refer [task-pipeline]]))

(def pipeline-components [:sync :queue :task-pipeline])

(defrecord OnyxPeerPipeline []
  component/Lifecycle
  (start [this]
    (component/start-system this pipeline-components))
  (stop [this]
    (component/stop-system this pipeline-components)))

(defn onyx-peer-pipeline
  [{:keys [sync queue payload]}]
  (map->OnyxPeerPipeline
   {:sync sync
    :queue queue
    :task-pipeline (component/using (task-pipeline payload)
                                    [:sync :queue])}))

(defn payload-loop [sync queue payload-ch shutdown-ch status-ch]
  (loop [pipeline nil]
    (when-let [[v ch] (alts!! [payload-ch shutdown-ch])]
      
      (when-not (nil? pipeline)
        (component/stop pipeline))
      
      (when (= ch payload-ch)
        (let [payload-node (:path v)
              payload (extensions/read-place sync payload-node)
              status-ch (chan 1)]
          
          (extensions/on-change sync (:status (:nodes payload)) #(>!! status-ch %))
          (extensions/touch-place sync (:ack (:nodes payload)))
          (<!! status-ch)
          
          (let [new-pipeline (onyx-peer-pipeline {:sync sync :queue queue :payload payload})]
            (component/start new-pipeline)
            (recur new-pipeline)))))))

(defrecord VirtualPeer []
  component/Lifecycle

  (start [{:keys [sync queue] :as component}]
    (prn "Starting Virtual Peer")

    (let [peer (extensions/create sync :peer)
          payload (extensions/create sync :payload)
          pulse (extensions/create sync :pulse)
          shutdown (extensions/create sync :shutdown)

          payload-ch (chan 1)
          shutdown-ch (chan 1)
          status-ch (chan 1)]
      
      (extensions/write-place sync peer {:pulse pulse :shutdown shutdown :payload payload})
      (extensions/on-change sync payload #(>!! payload-ch %))

      (dire/with-handler! #'payload-loop
        java.lang.Exception
        (fn [e & _]
          (.printStackTrace e)))

      (assoc component
        :peer-node peer
        :payload-node payload
        :pulse-node pulse
        :shutdown-node shutdown
        
        :payload-ch payload-ch
        :shutdown-ch shutdown-ch
        :status-ch status-ch

        :payload-thread (future (payload-loop sync queue payload-ch shutdown-ch status-ch)))))

  (stop [component]
    (prn "Stopping Virtual Peer")

    (close! (:payload-ch component))
    (close! (:shutdown-ch component))
    (close! (:status-ch component))

    (future-cancel (:payload-thread component))
    
    component))

(defn virtual-peer []
  (map->VirtualPeer {}))

