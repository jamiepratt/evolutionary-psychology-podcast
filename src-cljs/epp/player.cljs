(ns epp.player
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdomc]))

(defonce follow-root (atom nil))
(defonce player-state (r/atom {:active-index -1
                               :follow? true}))

(defn- number-value [value]
  (let [n (js/Number value)]
    (when (js/Number.isFinite n)
      n)))

(defn- dataset-value [element key]
  (some-> element .-dataset (aget key)))

(defn- phrase-record [element]
  {:element element
   :start (number-value (dataset-value element "start"))
   :end (number-value (dataset-value element "end"))})

(defn- query-all [selector]
  (array-seq (.querySelectorAll js/document selector)))

(defn- fmt-clock [seconds]
  (let [whole (long (max 0 (js/Math.floor (or seconds 0))))
        hours (quot whole 3600)
        minutes (quot (mod whole 3600) 60)
        seconds (mod whole 60)]
    (if (pos? hours)
      (str (.padStart (str hours) 2 "0") ":"
           (.padStart (str minutes) 2 "0") ":"
           (.padStart (str seconds) 2 "0"))
      (str (.padStart (str minutes) 2 "0") ":"
           (.padStart (str seconds) 2 "0")))))

(defn- button-label []
  (if (:follow? @player-state)
    "Following transcript"
    "Resume follow"))

(defn- update-play-button! [play-button audio]
  (when play-button
    (let [playing? (not (.-paused audio))]
      (.setAttribute play-button "aria-pressed" (str playing?))
      (.setAttribute play-button "aria-label" (if playing? "Pause audio" "Play audio"))
      (set! (.-textContent play-button) (if playing? "Pause" "Play")))))

(defn- update-time-display! [audio current-time duration-display waveform-canvas]
  (let [current (or (number-value (.-currentTime audio)) 0)
        duration (number-value (.-duration audio))]
    (when current-time
      (set! (.-textContent current-time) (fmt-clock current)))
    (when (and duration-display duration)
      (set! (.-textContent duration-display) (fmt-clock duration)))
    (when waveform-canvas
      (.setAttribute waveform-canvas "aria-valuenow" (str current))
      (when duration
        (.setAttribute waveform-canvas "aria-valuemax" (str duration))))))

(defn- enhance-custom-player! [audio custom-player]
  (when custom-player
    (.removeAttribute custom-player "hidden")
    (set! (.-hidden custom-player) false)
    (.removeAttribute audio "controls")
    (set! (.-hidden audio) true)))

(defn- follow-state-marker []
  [:span {:data-follow-state (if (:follow? @player-state)
                               "following"
                               "paused")}])

(defn- mount-follow-state! [follow-button]
  (when follow-button
    (let [marker-root (.createElement js/document "span")
          parent (.-parentNode follow-button)]
      (.setAttribute marker-root "hidden" "")
      (.setAttribute marker-root "data-player-enhancement-root" "")
      (if-let [next-sibling (.-nextSibling follow-button)]
        (.insertBefore parent marker-root next-sibling)
        (.appendChild parent marker-root))
      (reset! follow-root (rdomc/create-root marker-root))
      (rdomc/render @follow-root [follow-state-marker])
      (r/flush))))

(defn- update-follow-button! [follow-button]
  (when follow-button
    (.setAttribute follow-button "aria-pressed" (str (:follow? @player-state)))
    (set! (.-textContent follow-button) (button-label))))

(defn- set-follow! [follow-button follow?]
  (swap! player-state assoc :follow? follow?)
  (r/flush)
  (update-follow-button! follow-button))

(defn- find-phrase-index [phrases time]
  (loop [low 0
         high (dec (count phrases))
         candidate -1]
    (if (> low high)
      (if (= candidate -1)
        -1
        (let [phrase (nth phrases candidate)]
          (if (<= time (+ (:end phrase) 0.35))
            candidate
            candidate)))
      (let [mid (js/Math.floor (/ (+ low high) 2))
            phrase (nth phrases mid)]
        (if (<= (:start phrase) time)
          (recur (inc mid) high mid)
          (recur low (dec mid) candidate))))))

(defn- clear-active-phrase! [phrases]
  (let [active-index (:active-index @player-state)]
    (when (and (>= active-index 0)
               (< active-index (count phrases)))
      (let [element (:element (nth phrases active-index))]
        (.. element -classList (remove "is-active"))
        (.removeAttribute element "aria-current")))))

(defn- set-active! [phrases index should-scroll? follow?]
  (when (and (not= index (:active-index @player-state))
             (>= index 0)
             (< index (count phrases)))
    (clear-active-phrase! phrases)
    (swap! player-state assoc :active-index index)
    (let [active (:element (nth phrases index))
          turn (.closest active ".turn")]
      (.. active -classList (add "is-active"))
      (.setAttribute active "aria-current" "true")
      (doseq [node (query-all ".turn.is-current")]
        (when-not (= node turn)
          (.. node -classList (remove "is-current"))))
      (when turn
        (.. turn -classList (add "is-current")))
      (when (and should-scroll? follow?)
        true))))

(defn- sync-to-audio! [audio phrases scroll-active! should-scroll?]
  (let [index (find-phrase-index phrases (.-currentTime audio))
        prior (:active-index @player-state)]
    (set-active! phrases index should-scroll? (:follow? @player-state))
    (when (and should-scroll?
               (:follow? @player-state)
               (not= index prior)
               (>= index 0)
               (< index (count phrases)))
      (scroll-active! (:element (nth phrases index))))))

(defn- seek-to! [audio phrases follow-button scroll-active! seconds]
  (when (js/Number.isFinite seconds)
    (set-follow! follow-button true)
    (set! (.-currentTime audio) (max 0 seconds))
    (sync-to-audio! audio phrases scroll-active! true)
    (when-let [play-result (.play audio)]
      (when (.-catch play-result)
        (.catch play-result (fn [_]))))))

(defn- install-player! []
  (let [audio (.querySelector js/document "#episode-audio")
        transcript-root (.querySelector js/document ".transcript-shell")
        custom-player (.querySelector js/document "[data-custom-player]")
        play-button (.querySelector js/document "[data-play-toggle]")
        current-time (.querySelector js/document "[data-current-time]")
        duration-display (.querySelector js/document "[data-duration]")
        waveform-canvas (.querySelector js/document "[data-waveform-canvas]")
        follow-button (.querySelector js/document "[data-follow-toggle]")
        phrases (mapv phrase-record (query-all ".phrase"))
        suppress-scroll-pause? (atom false)
        scroll-pause-timer (atom 0)]
    (when (and audio
               transcript-root
               (seq phrases)
               (not= "true" (dataset-value audio "playerEnhanced")))
      (.setAttribute audio "data-player-enhanced" "true")
      (reset! player-state {:active-index -1
                            :follow? true})
      (enhance-custom-player! audio custom-player)
      (mount-follow-state! follow-button)
      (letfn [(scroll-active-into-view! [element]
                (reset! suppress-scroll-pause? true)
                (.scrollIntoView element #js {:block "center"
                                              :behavior "smooth"})
                (js/window.clearTimeout @scroll-pause-timer)
                (reset! scroll-pause-timer
                        (js/window.setTimeout
                         (fn [] (reset! suppress-scroll-pause? false))
                         650)))
              (sync! ([] (sync! true))
                ([should-scroll?]
                 (update-time-display! audio current-time duration-display waveform-canvas)
                 (sync-to-audio! audio phrases scroll-active-into-view! should-scroll?)))
              (seek! [seconds]
                (seek-to! audio phrases follow-button scroll-active-into-view! seconds))]
        (.addEventListener
         transcript-root
         "click"
         (fn [event]
           (let [target (.-target event)
                 phrase (when target (.closest target ".phrase"))]
             (if phrase
               (seek! (number-value (dataset-value phrase "start")))
               (when-let [timestamp (when target (.closest target "[data-seek]"))]
                 (.preventDefault event)
                 (seek! (number-value (dataset-value timestamp "seek"))))))))
        (.addEventListener
         transcript-root
         "keydown"
         (fn [event]
           (when (and (#{"Enter" " "} (.-key event)))
             (when-let [phrase (some-> event .-target (.closest ".phrase"))]
               (.preventDefault event)
               (seek! (number-value (dataset-value phrase "start")))))))
        (.addEventListener
         js/window
         "scroll"
         (fn [_]
           (when (and (not @suppress-scroll-pause?)
                      (not (.-paused audio)))
             (set-follow! follow-button false)))
         #js {:passive true})
        (when follow-button
          (.addEventListener
           follow-button
           "click"
           (fn [_]
             (let [follow? (not (:follow? @player-state))]
               (set-follow! follow-button follow?)
               (when follow?
                 (sync! true))))))
        (when play-button
          (.addEventListener
           play-button
           "click"
           (fn [_]
             (if (.-paused audio)
               (when-let [play-result (.play audio)]
                 (when (.-catch play-result)
                   (.catch play-result (fn [_]))))
               (.pause audio))
             (update-play-button! play-button audio))))
        (.addEventListener audio "timeupdate" #(sync! true))
        (.addEventListener
         audio
         "play"
         (fn [_]
           (set-follow! follow-button true)
           (update-play-button! play-button audio)
           (sync! true)))
        (.addEventListener
         audio
         "pause"
         (fn [_]
           (update-play-button! play-button audio)
           (sync! false)))
        (.addEventListener
         audio
         "seeked"
         (fn [_]
           (set-follow! follow-button true)
           (sync! true)))
        (.addEventListener audio "loadedmetadata" #(sync! false))
        (update-play-button! play-button audio)
        (update-follow-button! follow-button)
        (sync! false)))))

(defn init! []
  (if (= "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" install-player!)
    (install-player!)))
