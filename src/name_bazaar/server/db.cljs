(ns name-bazaar.server.db
  (:require
    [cljs.core.async :refer [<! >! chan]]
    [cljs.spec.alpha :as s]
    [district0x.server.honeysql-extensions]
    [district0x.server.state :as state]
    [district0x.shared.utils :as d0x-shared-utils :refer [combination-of? collify]]
    [district0x.server.db-utils :refer [log-error db-get db-run! db-all keywords->sql-cols sql-results-chan order-by-closest-like]]
    [honeysql.helpers :as sql-helpers :refer [merge-where merge-order-by merge-left-join]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

; name VARCHAR NOT NULL,
; name_rowid INTEGER DEFAULT NULL,
; FOREIGN KEY(name_rowid) REFERENCES ens_names(rowid)

(defn create-tables! [db]
  (.serialize db (fn []
                   #_(.run db "CREATE VIRTUAL TABLE ens_names USING fts5(name)" log-error)

                   (.run db "CREATE TABLE offerings (
                          address CHAR(42) PRIMARY KEY NOT NULL,
                          created_on UNSIGNED INTEGER NOT NULL,
                          node CHAR(66) NOT NULL,
                          name VARCHAR NOT NULL,
                          original_owner CHAR(42) NOT NULL,
                          new_owner CHAR(42) DEFAULT NULL,
                          version UNSIGNED INTEGER NOT NULL,
                          price UNSIGNED BIG INT NOT NULL,
                          name_level UNSIGNED INTEGER DEFAULT 0,
                          label_length UNSIGNED INTEGER DEFAULT 0,
                          contains_number BOOLEAN NOT NULL DEFAULT false,
                          contains_special_char BOOLEAN NOT NULL DEFAULT false,
                          node_owner BOOLEAN NOT NULL DEFAULT false,
                          end_time UNSIGNED INTEGER DEFAULT NULL,
                          bid_count UNSIGNED INTEGER DEFAULT 0
                          )" log-error)
                   (.run db "CREATE INDEX created_on_index ON offerings (created_on)" log-error)
                   (.run db "CREATE INDEX name_index ON offerings (name)" log-error)
                   (.run db "CREATE INDEX node_index ON offerings (node)" log-error)
                   (.run db "CREATE INDEX original_owner_index ON offerings (original_owner)" log-error)
                   (.run db "CREATE INDEX new_owner_index ON offerings (new_owner)" log-error)
                   (.run db "CREATE INDEX price_index ON offerings (price)" log-error)
                   (.run db "CREATE INDEX end_time_index ON offerings (end_time)" log-error)
                   (.run db "CREATE INDEX name_level_index ON offerings (name_level)" log-error)
                   (.run db "CREATE INDEX label_length_index ON offerings (label_length)" log-error)
                   (.run db "CREATE INDEX contains_number_index ON offerings (contains_number)" log-error)
                   (.run db "CREATE INDEX contains_special_char_index ON offerings (contains_special_char)" log-error)
                   (.run db "CREATE INDEX node_owner_index ON offerings (node_owner)" log-error)
                   (.run db "CREATE INDEX version_index ON offerings (version)" log-error)
                   (.run db "CREATE INDEX bid_count_index ON offerings (bid_count)" log-error)

                   (.run db "CREATE TABLE bids (
                          bidder CHAR(42) NOT NULL,
                          value UNSIGNED INTEGER NOT NULL,
                          offering CHAR(42) NOT NULL,
                          FOREIGN KEY(offering) REFERENCES offerings(address)
                          )" log-error)

                   (.run db "CREATE INDEX bidder_index ON bids (bidder)" log-error)
                   (.run db "CREATE INDEX bid_value_index ON bids (value)" log-error)

                   (.run db "CREATE TABLE offering_requests (
                          node CHAR(66) PRIMARY KEY NOT NULL,
                          name VARCHAR NOT NULL,
                          requesters_count UNSIGNED INTEGER NOT NULL DEFAULT 0
                          )" log-error)

                   (.run db "CREATE INDEX requesters_count_index ON offering_requests (requesters_count)" log-error)
                   (.run db "CREATE INDEX offering_requests_name_index ON offering_requests (name)" log-error)
                   )))

(def offering-keys [:offering/address
                    :offering/created-on
                    :offering/node
                    :offering/name
                    :offering/original-owner
                    :offering/new-owner
                    :offering/version
                    :offering/price
                    :offering/name-level
                    :offering/label-length
                    :offering/contains-number?
                    :offering/contains-special-char?
                    :offering/node-owner?
                    :auction-offering/end-time
                    :auction-offering/bid-count
                    :auction-offering/bid-count])

(defn upsert-offering! [db values]
  (db-run! db {:insert-or-replace-into :offerings
               :columns (keywords->sql-cols offering-keys)
               :values [((apply juxt offering-keys) values)]}))

(defn set-offering-node-owner?! [db {:keys [:offering/node-owner? :offering/address]}]
  (db-run! db {:update :offerings
               :set {:node-owner node-owner?}
               :where [:= :address address]}))

(defn offering-exists? [db offering-address]
  (db-get db {:select [1]
              :from [:offerings]
              :where [:= :address offering-address]}))

(def bids-keys [:bid/bidder :bid/value :bid/offering])

(defn insert-bid! [db values]
  (db-run! db {:insert-into :bids
               :columns (keywords->sql-cols bids-keys)
               :values [((apply juxt bids-keys) values)]}))

(def offering-requests-keys [:offering-request/node
                             :offering-request/name
                             :offering-request/requesters-count])

(defn upsert-offering-requests! [db values]
  (db-run! db {:insert-or-replace-into :offering-requests
               :columns (keywords->sql-cols offering-requests-keys)
               :values [((apply juxt offering-requests-keys) values)]}))

(defn- name-pattern [name name-position]
  (condp = name-position
    :contain (str "%" name "%")
    :start (str name "%")
    :ens (str "%" name)
    (str "%" name "%")))


(s/def ::order-by-dir (partial contains? #{:desc :asc}))
(s/def ::offering-order-by-column (partial contains? #{:price :end-time :created-on :bid-count}))
(s/def ::offerings-order-by-item (s/tuple ::offering-order-by-column ::order-by-dir))
(s/def ::offerings-order-by (s/coll-of ::offerings-order-by-item :kind vector? :distinct true))
(s/def ::offerings-select-fields (partial combination-of? #{:address :node :version :name}))

(defn search-offerings [db {:keys [:original-owner :new-owner :node :name :min-price :max-price :buy-now? :auction?
                                   :min-length :max-length :name-position :max-end-time :version :node-owner?
                                   :top-level-names? :sub-level-names? :exclude-special-chars? :exclude-numbers?
                                   :limit :offset :order-by :select-fields :root-name :total-count? :bidder]
                            :or {offset 0 limit -1 root-name "eth"}}]
  (let [select-fields (collify select-fields)
        select-fields (if (s/valid? ::offerings-select-fields select-fields) select-fields [:address])
        min-price (js/parseInt min-price)
        max-price (js/parseInt max-price)]
    (db-all db
            (cond-> {:select select-fields
                     :from [:offerings]
                     :offset offset
                     :limit limit}
              original-owner (merge-where [:= :original-owner original-owner])
              new-owner (merge-where [:= :new-owner new-owner])
              (not (js/isNaN min-price)) (merge-where [:>= :price min-price])
              (not (js/isNaN max-price)) (merge-where [:<= :price max-price])
              min-length (merge-where [:>= :label-length min-length])
              max-length (merge-where [:<= :label-length max-length])
              max-end-time (merge-where [:<= :end-time max-end-time])
              bidder (merge-left-join [:bids :b] [:= :b.offering :offerings.address])
              bidder (merge-where [:= :b.bidder bidder])
              version (merge-where [:= :version version])
              (boolean? node-owner?) (merge-where [:= :node-owner node-owner?])
              (or buy-now? auction?) (merge-where [:or
                                                   (when buy-now?
                                                     [:< :version 100000])
                                                   (when auction?
                                                     [:>= :version 100000])])
              (or top-level-names? sub-level-names?) (merge-where [:or
                                                                   (when top-level-names?
                                                                     [:<= :name-level 1])
                                                                   (when sub-level-names?
                                                                     [:> :name-level 1])])
              exclude-special-chars? (merge-where [:= :contains-special-char false])
              exclude-numbers? (merge-where [:= :contains-number false])
              node (merge-where [:= :node node])
              name (merge-where [:like :name (str (name-pattern name (keyword name-position)) "." root-name)])
              name (merge-order-by (order-by-closest-like :name name {:suffix (str "." root-name)}))
              name (merge-order-by :name)
              (and (not name) (s/valid? ::offerings-order-by order-by)) (merge-order-by order-by))
            {:total-count? total-count?
             :port (sql-results-chan select-fields)})))

(s/def ::offering-requests-order-by-column (partial contains? #{:requesters-count}))
(s/def ::offering-requests-order-by-item (s/tuple ::offering-requests-order-by-column ::order-by-dir))
(s/def ::offering-requests-order-by (s/coll-of ::offering-requests-order-by-item :kind vector? :distinct true))
(s/def ::offering-requests-select-fields (partial combination-of? #{:node :name :requesters-count}))

(defn search-offering-requests [db {:keys [:limit :offset :name :name-position
                                           :order-by :root-name :select-fields :total-count?]
                                    :or {offset 0 limit -1 root-name "eth"}}]
  (let [select-fields (collify select-fields)
        select-fields (if (s/valid? ::offering-requests-select-fields select-fields) select-fields [:node])]
    (db-all db
            (cond-> {:select select-fields
                     :from [:offering-requests]
                     :offset offset
                     :limit limit}
              name (merge-where [:like :name (str (name-position name (keyword name-position)) "." root-name)])
              name (merge-order-by (order-by-closest-like :name name {:suffix (str "." root-name)}))
              name (merge-order-by :name)
              (and (not name) (s/valid? ::offering-requests-order-by order-by)) (merge {:order-by order-by}))
            {:port (sql-results-chan select-fields)
             :total-count? total-count?})))


