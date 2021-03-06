(ns name-bazaar.ui.events.reverse-registrar-events
  (:require
    [cljs.spec.alpha :as s]
    [district0x.ui.spec-interceptors :refer [validate-first-arg]]
    [district0x.ui.utils :as d0x-ui-utils :refer [path-with-query truncate]]
    [name-bazaar.ui.utils :refer [path-for reverse-record-node]]
    [goog.string :as gstring]
    [name-bazaar.ui.constants :as constants :refer [default-gas-price interceptors]]
    [re-frame.core :as re-frame :refer [reg-event-fx]]
    [taoensso.timbre :as logging :refer-macros [info warn error]]))

(reg-event-fx
  :reverse-registrar/claim-with-resolver
  [interceptors (validate-first-arg (s/keys :req [:ens.record/addr]))]
  (fn [{:keys [:db]} [form-data]]
    (let [form-data (update form-data
                            :public-resolver #(or % (get-in db [:smart-contracts :public-resolver :address])))]
      {:dispatch [:district0x/make-transaction
                  {:name (gstring/format "Setup resolver for %s" (:ens.record/addr form-data))
                   :contract-key :reverse-registrar
                   :contract-method :claim-with-resolver
                   :form-data (select-keys form-data [:ens.record/addr :public-resolver])
                   :args-order [:ens.record/addr :public-resolver]
                   :form-id (select-keys form-data [:ens.record/addr])
                   :tx-opts {:gas 100000 :gas-price default-gas-price}
                   :on-tx-receipt-n [[:ens.records.resolver/load [(reverse-record-node (:ens.record/addr form-data))]]
                                     [:district0x.snackbar/show-message
                                      (gstring/format
                                        "Resolver for %s has been set up" (truncate (:ens.record/addr form-data) 10))]]}]})))
