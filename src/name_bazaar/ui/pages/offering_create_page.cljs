(ns name-bazaar.ui.pages.offering-create-page
  (:require [district0x.ui.components.misc :as misc :refer [row row-with-cols col center-layout paper page]]))

(defmethod page :route.offering/create []
  [:div "Create new offering page"])