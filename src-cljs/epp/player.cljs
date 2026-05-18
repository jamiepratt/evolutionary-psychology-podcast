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

(defn- clamp-percent [value]
  (-> value
      (max 0)
      (min 100)))

(defn- overview-percent [current duration]
  (if (and duration (pos? duration))
    (clamp-percent (* (/ current duration) 100))
    0))

(defn- toggle-playback! [audio play-button]
  (if (.-paused audio)
    (when-let [play-result (.play audio)]
      (when (.-catch play-result)
        (.catch play-result (fn [_]))))
    (.pause audio))
  (update-play-button! play-button audio))

(defn- update-overview-cursor! [overview-cursor current duration]
  (when overview-cursor
    (set! (.. overview-cursor -style -left)
          (str (overview-percent current duration) "%"))))

(defn- update-time-display! [audio current-time duration-display waveform-canvas overview-cursor]
  (let [current (or (number-value (.-currentTime audio)) 0)
        duration (number-value (.-duration audio))]
    (when current-time
      (set! (.-textContent current-time) (fmt-clock current)))
    (when (and duration-display duration)
      (set! (.-textContent duration-display) (fmt-clock duration)))
    (update-overview-cursor! overview-cursor current duration)
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

(def default-bucket-seconds 0.02)
(def waveform-pixels-per-bucket 2)

(defn- clamp-time [audio seconds]
  (let [duration (number-value (.-duration audio))
        lower-bound (max 0 seconds)]
    (if (and duration (pos? duration))
      (min duration lower-bound)
      lower-bound)))

(defn- waveform-start-time [current visible-seconds]
  (- current (/ visible-seconds 2)))

(defn- waveform-visible-seconds [display-width bucket-seconds]
  (* (max 1 (/ display-width waveform-pixels-per-bucket)) bucket-seconds))

(defn- canvas-bucket-seconds [canvas]
  (or (number-value (dataset-value canvas "waveformBucketSeconds"))
      default-bucket-seconds))

(defn- manifest-bucket-seconds [manifest]
  (or (number-value (aget manifest "bucket_seconds"))
      default-bucket-seconds))

(defn- waveform-event-time [audio canvas event]
  (let [rect (.getBoundingClientRect canvas)
        width (max 1 (or (number-value (.-width rect)) 0))
        left (or (number-value (.-left rect)) 0)
        x (-> (- (or (number-value (.-clientX event)) left) left)
              (max 0)
              (min width))
        bucket-seconds (canvas-bucket-seconds canvas)
        visible-seconds (waveform-visible-seconds width bucket-seconds)
        current (or (number-value (.-currentTime audio)) 0)
        start-time (waveform-start-time current visible-seconds)]
    (clamp-time audio (+ start-time (* (/ x width) visible-seconds)))))

(defn- resize-canvas! [canvas]
  (let [ratio (or (number-value (.-devicePixelRatio js/window)) 1)
        css-width (or (number-value (.-clientWidth canvas)) 600)
        css-height (or (number-value (.-clientHeight canvas)) 72)
        width (long (* css-width ratio))
        height (long (* css-height ratio))]
    (when (or (not= (.-width canvas) width)
              (not= (.-height canvas) height))
      (set! (.-width canvas) width)
      (set! (.-height canvas) height))
    {:width width
     :height height
     :css-width css-width
     :ratio ratio}))

(defn- render-waveform! [audio canvas ctx manifest peaks]
  (let [{:keys [width height css-width ratio]} (resize-canvas! canvas)
        bucket-seconds (manifest-bucket-seconds manifest)
        peak-count (or (number-value (aget manifest "peak_count"))
                       (/ (.-length peaks) 2))
        visible-seconds (waveform-visible-seconds css-width bucket-seconds)
        current (or (number-value (.-currentTime audio)) 0)
        start-time (waveform-start-time current visible-seconds)
        center (/ height 2)
        scale (/ height 2)]
    (.setAttribute canvas "data-waveform-bucket-seconds" (str bucket-seconds))
    (.clearRect ctx 0 0 width height)
    (set! (.-lineWidth ctx) 1)
    (set! (.-strokeStyle ctx) "#0f766e")
    (.beginPath ctx)
    (loop [display-x 0]
      (when (< display-x css-width)
        (let [bucket-offset (/ display-x waveform-pixels-per-bucket)
              time (+ start-time (* bucket-offset bucket-seconds))
              peak-index (long (js/Math.floor (/ time bucket-seconds)))
              canvas-x (* display-x ratio)]
          (when (and (>= peak-index 0)
                     (< peak-index peak-count))
            (let [min-sample (aget peaks (* peak-index 2))
                  max-sample (aget peaks (inc (* peak-index 2)))
                  y-top (- center (* (/ max-sample 32768) scale))
                  y-bottom (- center (* (/ min-sample 32768) scale))]
              (.moveTo ctx canvas-x y-top)
              (.lineTo ctx canvas-x y-bottom))))
        (recur (+ display-x waveform-pixels-per-bucket))))
    (.stroke ctx)))

(defn- render-loading-waveform! [canvas ctx frame-time]
  (let [{:keys [width height css-width ratio]} (resize-canvas! canvas)
        seconds (/ (or (number-value frame-time) 0) 1000)
        center (/ height 2)
        max-height (* height 0.34)
        step 7]
    (.clearRect ctx 0 0 width height)
    (set! (.-lineWidth ctx) (max 1 (* 2 ratio)))
    (set! (.-lineCap ctx) "round")
    (set! (.-strokeStyle ctx) "rgba(94, 234, 212, 0.5)")
    (.beginPath ctx)
    (loop [display-x 4]
      (when (< display-x css-width)
        (let [phase (+ (* seconds 3.2) (* display-x 0.055))
              shimmer (+ 0.42 (* 0.58 (js/Math.pow (js/Math.abs (js/Math.sin phase)) 1.4)))
              canvas-x (* display-x ratio)
              half-height (* max-height shimmer)]
          (.moveTo ctx canvas-x (- center half-height))
          (.lineTo ctx canvas-x (+ center half-height)))
        (recur (+ display-x step))))
    (.stroke ctx)))

(defn- start-waveform-loading-renderer! [canvas]
  (when-let [ctx (when canvas (.getContext canvas "2d"))]
    (when (.-requestAnimationFrame js/window)
      (let [running? (atom true)
            raf-id (atom 0)]
        (letfn [(draw! [frame-time]
                  (when @running?
                    (render-loading-waveform! canvas ctx frame-time)
                    (reset! raf-id (.requestAnimationFrame js/window draw!))))]
          (reset! raf-id (.requestAnimationFrame js/window draw!))
          (fn []
            (when @running?
              (reset! running? false)
              (when (and (pos? @raf-id) (.-cancelAnimationFrame js/window))
                (.cancelAnimationFrame js/window @raf-id))
              (.clearRect ctx 0 0 (.-width canvas) (.-height canvas)))))))))

(defn- start-waveform-renderer! [audio canvas manifest peaks]
  (when-let [ctx (when canvas (.getContext canvas "2d"))]
    (when (.-requestAnimationFrame js/window)
      (letfn [(draw! [_]
                (render-waveform! audio canvas ctx manifest peaks)
                (.requestAnimationFrame js/window draw!))]
        (.requestAnimationFrame js/window draw!)))))

(defn- load-waveform! [audio canvas]
  (let [manifest-path (dataset-value audio "waveformManifest")]
    (when (and canvas manifest-path (.-fetch js/window))
      (let [stop-loading (start-waveform-loading-renderer! canvas)]
        (letfn [(stop-loading! []
                  (when stop-loading
                    (stop-loading)))]
          (-> (.fetch js/window manifest-path)
              (.then (fn [manifest-response]
                       (if (.-ok manifest-response)
                         (-> (.json manifest-response)
                             (.then
                              (fn [manifest]
                                (.setAttribute canvas "data-waveform-bucket-seconds"
                                               (str (manifest-bucket-seconds manifest)))
                                (if-let [peaks-path (aget manifest "peaks")]
                                  (-> (.fetch js/window peaks-path)
                                      (.then
                                       (fn [peaks-response]
                                         (if (.-ok peaks-response)
                                           (.arrayBuffer peaks-response)
                                           (do
                                             (stop-loading!)
                                             nil))))
                                      (.then
                                       (fn [array-buffer]
                                         (if array-buffer
                                           (do
                                             (stop-loading!)
                                             (start-waveform-renderer!
                                              audio
                                              canvas
                                              manifest
                                              (js/Int16Array. array-buffer)))
                                           (stop-loading!)))))
                                  (stop-loading!)))))
                         (stop-loading!))))
              (.catch (fn [_]
                        (stop-loading!)
                        nil))))))))

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
    (set! (.-currentTime audio) (clamp-time audio seconds))
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
        overview-cursor (.querySelector js/document "[data-overview-cursor]")
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
      (load-waveform! audio waveform-canvas)
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
                 (update-time-display!
                  audio
                  current-time
                  duration-display
                  waveform-canvas
                  overview-cursor)
                 (sync-to-audio! audio phrases scroll-active-into-view! should-scroll?)))
              (seek! [seconds]
                (seek-to! audio phrases follow-button scroll-active-into-view! seconds)
                (sync! true))
              (seek-from-waveform! [event]
                (.preventDefault event)
                (seek! (waveform-event-time audio waveform-canvas event)))
              (schedule-waveform-seek! [pending-time raf-id seconds]
                (reset! pending-time seconds)
                (when (zero? @raf-id)
                  (reset! raf-id
                          (.requestAnimationFrame
                           js/window
                           (fn [_]
                             (reset! raf-id 0)
                             (seek! @pending-time))))))
              (handle-waveform-key! [event]
                (let [key (.-key event)
                      current (or (number-value (.-currentTime audio)) 0)]
                  (case key
                    "ArrowLeft" (do (.preventDefault event) (seek! (- current 5)))
                    "ArrowRight" (do (.preventDefault event) (seek! (+ current 5)))
                    "Home" (do (.preventDefault event) (seek! 0))
                    "End" (do (.preventDefault event)
                              (when-let [duration (number-value (.-duration audio))]
                                (seek! duration)))
                    (" " "Enter") (do (.preventDefault event)
                                      (toggle-playback! audio play-button))
                    nil)))]
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
        (when waveform-canvas
          (let [dragging? (atom false)
                pending-time (atom nil)
                raf-id (atom 0)]
            (.addEventListener
             waveform-canvas
             "pointerdown"
             (fn [event]
               (reset! dragging? true)
               (when (.-setPointerCapture waveform-canvas)
                 (.setPointerCapture waveform-canvas (.-pointerId event)))
               (seek-from-waveform! event)))
            (.addEventListener
             waveform-canvas
             "pointermove"
             (fn [event]
               (when @dragging?
                 (.preventDefault event)
                 (schedule-waveform-seek!
                  pending-time
                  raf-id
                  (waveform-event-time audio waveform-canvas event)))))
            (.addEventListener
             waveform-canvas
             "pointerup"
             (fn [event]
               (reset! dragging? false)
               (when (.-releasePointerCapture waveform-canvas)
                 (.releasePointerCapture waveform-canvas (.-pointerId event)))))
            (.addEventListener
             waveform-canvas
             "pointercancel"
             (fn [_]
               (reset! dragging? false)))
            (.addEventListener waveform-canvas "keydown" handle-waveform-key!)))
        (when play-button
          (.addEventListener
           play-button
           "click"
           (fn [_] (toggle-playback! audio play-button))))
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
