(ns name-bazaar.server.syncer
  (:require
    [bignumber.core :as bn]
    [cljs-web3.core :as web3]
    [cljs-web3.eth :as web3-eth]
    [clojure.string :as string]
    [district.server.config :refer [config]]
    [district.server.web3 :refer [web3]]
    [district0x.shared.utils :refer [prepend-address-zeros]]
    [mount.core :as mount :refer [defstate]]
    [name-bazaar.server.contracts-api.auction-offering :as auction-offering]
    [name-bazaar.server.contracts-api.ens :as ens]
    [name-bazaar.server.contracts-api.offering :as offering]
    [name-bazaar.server.contracts-api.offering-registry :as offering-registry]
    [name-bazaar.server.contracts-api.offering-requests :as offering-requests]
    [name-bazaar.server.contracts-api.registrar :as registrar]
    [name-bazaar.server.db :as db]
    [name-bazaar.server.deployer]
    [name-bazaar.server.generator]
    [district.server.smart-contracts :refer [replay-past-events]]
    [taoensso.timbre :as logging :refer-macros [info warn error]]))

(declare start)
(declare stop)
(defstate ^{:on-reload :noop} syncer
  :start (start (merge (:syncer @config)
                       (:syncer (mount/args))))
  :stop (stop syncer))

(def info-text "Handling blockchain event")
(def error-text "Error handling blockchain event")

(defn node-owner? [offering-address {:keys [:offering/node :offering/top-level-name? :offering/label]}]
  (let [ens-owner (ens/owner {:ens.record/node node})]
    (and (= ens-owner offering-address)
         (if top-level-name?
           (= (registrar/entry-deed-owner {:ens.record/label label})
              offering-address)
           true))))


(defn get-offering [offering-address]
  (let [offering (offering/get-offering offering-address)
        auction-offering (when (:offering/auction? offering)
                           (auction-offering/get-auction-offering offering-address))
        owner? (node-owner? offering-address offering)
        offering (-> offering
                   (merge auction-offering)
                   (assoc :offering/node-owner? owner?))]
    offering))


(defn on-offering-changed [err {:keys [:args]}]
  (info info-text {:args args} ::on-offering-changed)
  (try
    (let [offering (get-offering (:offering args))]
      (if (and (:offering/valid-name? offering)
               (:offering/normalized? offering))
        (db/upsert-offering! offering)
        (warn [:MAILFORMED-NAME-OFFERING offering])))
    (catch :default e
      (error error-text {:args args :error (ex-message e)} ::on-offering-changed))))


(defn on-offering-bid [err {{:keys [:offering :version :extra-data] :as args} :args}]
  (info info-text {:args args} ::on-offering-bid)
  (try
    (when-not (db/offering-exists? offering)
      (db/upsert-offering! (get-offering offering)))

    (-> (zipmap [:bid/bidder :bid/value :bid/datetime] extra-data)
      (update :bid/bidder (comp prepend-address-zeros web3/from-decimal))
      (update :bid/value bn/number)
      (update :bid/datetime bn/number)
      (assoc :bid/offering offering)
      (->> (db/insert-bid!)))
    (catch :default e
      (error error-text {:args args :error (ex-message e)} ::on-offering-bid))))


(defn on-request-added [err {{:keys [:node :round :requesters-count] :as args} :args}]
  (info info-text {:args args} ::on-request-added)
  (try
    (db/upsert-offering-requests-rounds!
      {:offering-request/node node
       :offering-request/round (bn/number round)
       :offering-request/requesters-count (bn/number requesters-count)})
    (catch :default e
      (error error-text {:args args :error (ex-message e)} ::on-request-added))))


(defn on-round-changed [err {{:keys [:node :latest-round] :as args} :args}]
  (info info-text {:args args} ::on-round-changed)
  (try
    (let [latest-round (bn/number latest-round)
          request (offering-requests/get-request {:offering-request/node node})]
      (db/upsert-offering-requests! (assoc request :offering-request/latest-round latest-round))
      (when (= latest-round (:offering-request/latest-round request))
        ;; This is optimisation so we don't have to go through all on-request-added from block 0
        ;; We just save current count of latest round, because it's all we need. Don't need all history
        (on-request-added nil {:args {:node node
                                      :round latest-round
                                      :requesters-count (:offering-request/requesters-count request)}})))
    (catch :default e
      (error error-text {:args args :error (ex-message e)} ::on-round-changed))))


(defn on-ens-transfer [err {{:keys [:node :owner] :as args} :args}]
  (try
    (when (db/offering-exists? owner)
      (let [offering (offering/get-offering owner)]
        (logging/info info-text {:args args} ::on-ens-new-owner)
        (db/set-offering-node-owner?! {:offering/address owner
                                       :offering/node-owner? (node-owner? owner offering)})))
    (catch :default e
      (error error-text {:args args :error (ex-message e)} ::on-ens-transfer))))


(defn start [{:keys [:delay]
              :or {delay 0}
              :as args}]
  (when-not (web3/connected? @web3)
    (throw (js/Error. "Can't connect to Ethereum node")))
  [(offering-registry/on-offering-added {} "latest" on-offering-changed)
   (offering-registry/on-offering-changed {} "latest" on-offering-changed)
   (offering-requests/on-request-added {} "latest" on-request-added)
   (offering-requests/on-round-changed {} "latest" on-round-changed)
   (offering-registry/on-offering-changed {:event-type "bid"} "latest" on-offering-bid)
   (ens/on-new-owner {} "latest" on-ens-transfer)
   (ens/on-transfer {} "latest" on-ens-transfer)

   (-> (offering-registry/on-offering-added {} {:from-block 0 :to-block "latest"})
     (replay-past-events on-offering-changed {:delay delay}))

   (-> (offering-requests/on-round-changed {} {:from-block 0 :to-block "latest"})
     (replay-past-events on-round-changed {:delay delay}))

   (-> (offering-registry/on-offering-changed {:event-type "bid"} {:from-block 0 :to-block "latest"})
     (replay-past-events on-offering-bid {:delay delay}))])


(defn stop [syncer]
  (doseq [filter (remove nil? @syncer)]
    (web3-eth/stop-watching! filter (fn [err]))))
