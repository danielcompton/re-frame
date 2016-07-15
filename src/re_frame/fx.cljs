(ns re-frame.fx
  (:require [reagent.ratom  :refer [IReactiveAtom]]
            [re-frame.router :refer [dispatch]]
            [re-frame.db :refer [app-db]]
            [re-frame.events]
            [re-frame.loggers    :refer [console]]))


;; -- Registration ------------------------------------------------------------

(def ^:private id->handler-fn  (atom {}))

(defn lookup-handler
  [effect-id]
  (get @id->handler-fn effect-id))


(defn clear-all-handlers!
  []
  (reset! id->handler-fn {}))


(defn clear-handler!
  [effect-id]
  (if (lookup-handler effect-id)
    (swap! id->handler-fn dissoc effect-id)
    (console :warn "re-frame: unable to clear effect handler for  " effect-id ". Not defined.")))


(defn register
  "register a handler fn for an effect."
  [effect-id handler-fn]
  (when (lookup-handler effect-id)
    (console :warn "re-frame: overwriting an effects handler for: " effect-id))   ;; allow it, but warn.
  (swap! id->handler-fn assoc effect-id handler-fn))


;; -- Standard Builtin Effects Handlers  --------------------------------------

(defn dispatch-helper
  [effect]
  (cond
    (list? effect)   (map dispatch effect)
    (vector? effect) (dispatch effect)
    :else (console :error "re-frame: expected :dispatch effect to be a list or vector, but got: " effect)))

;; Example:
;; {:dispatch-later {200  [:event-id "param"]    ;;  in 200ms do this: (dispatch [:event-id "param"])
;;                   100  [:also :this :in :100ms]
;;                   250  (list [:do ] [:all ] [:three ])}
;;
(register
  :dispatch-later
  (fn [effect]
    (doseq  [[ms events] effect]
        (js/setTimeout #(dispatch-helper events) ms))))


;; Supply either a vector or a list of vectors. For example:
;;
;;   {:dispatch [:event-id "param"] }
;;
;;   {:dispatch (list [:do :all] [:three :of] [:these]) }
;;
(register
  :dispatch
  (fn [val]
    (dispatch-helper val)))


;;
;; {:forward-events  {:listen      :an-id-for-this-listner
;;                    :events      #{:event1  :event2}
;;                    :dispatch-to [:eid "eg. param"]}     ;; the forwared event will be conj to the end of the dispatch.
;;
;; {:forward-events  {:unlisten :the-listner-id-I-originally-supplied}}
;;
#_(register
  :forward-events
  (let [id->listen-fn (atom {})
        process-one-entry (fn [{:as m :keys [listen events dispatch-to unlisten]}]
                            (let [_  (assert (map? m) (str "re-frame: effects handler for :forward-events expected a map or a list of maps. Got: " m))]   ;; XXX do this better
                              (if  unlisten
                                (do
                                  (re-frame.core/remove-post-event-callback (@id->listen-fn unlisten))
                                  (swap! id->listen-fn dissoc unlisten))
                                (let [post-event-callback-fn  (fn [event-v _]
                                                                (when (events (first event-v))
                                                                  (dispatch (conj dispatch-to event-v))))]
                                  (re-frame.core/add-post-event-callback  post-event-callback-fn)
                                  (swap! id->listen-fn assoc listen post-event-callback-fn)))))]
    (fn [val]
      (cond
        (map? val) (process-one-entry val)
        (list? val) (doall (map process-one-entry val)))     ;; XXX add else
      )))


(register
  :deregister-event-handler
  (fn [val]
    (if (list? val)
      (doall (map re-frame.events/clear-handler! val))
      (re-frame.events/clear-handler! val))))


(register
  :db
  (fn [val]
    (reset! app-db val)))

;; -- Middleware --------------------------------------------------------------

;; XXX a coeffect for jsDate ?
;; XXX add metadata saying it is fx.
;; XXX add config
;; XXX world or branch ??  Return world?
;; XXX ordering
;; XXX review other standard middleware
;; XXX think about an undo effect


(defn fx
  [handler]
  (fn fx-handler
    [app-db event-vec]
    (if-not (satisfies? IReactiveAtom app-db)
        (if (map? app-db)
          (console :warn "re-frame: Looks like you might be using \"fx\" middleware with \"def-event\".  Use \"def-event-fx\" to put Or something. ")
          (console :warn "re-frame: \"pure\" middleware not given a Ratom.  Got: " app-db)))
    (let [world   {:db @app-db}]
      (->> (handler world event-vec)
           (map (fn [[key val]]
                  (if-let [effect-fn  (lookup-handler key)]
                    (effect-fn val)
                    (console :error "re-frame: no effects handler defined for: " key ". Ignoring"))))))))