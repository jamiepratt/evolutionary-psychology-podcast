(ns epp.player
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdomc]))

(defonce follow-root (atom nil))
(defonce player-state (r/atom {:active-index -1
                               :follow? true
                               :waveform-window-seconds 30
                               :waveform-center-time nil
                               :selected-segment-id nil}))

(def waveform-window-presets [{:seconds 30 :label "30s"}
                              {:seconds 120 :label "2min"}
                              {:seconds 300 :label "5min"}])
(def default-waveform-window-seconds 30)

(defn- number-value [value]
  (let [n (js/Number value)]
    (when (js/Number.isFinite n)
      n)))

(defn- dataset-value [element key]
  (some-> element .-dataset (aget key)))

(defn- phrase-record [element]
  {:id (or (some-> element .-id) "")
   :kind "phrase"
   :element element
   :start (number-value (dataset-value element "start"))
   :end (number-value (dataset-value element "end"))})

(defn- phrase-id [phrase]
  (or (:id phrase) ""))

(defn- query-all [selector]
  (array-seq (.querySelectorAll js/document selector)))

(defn- button-label []
  (if (:follow? @player-state)
    "Following transcript"
    "Resume follow"))

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
  (swap! player-state
         (fn [state]
           (cond-> (assoc state :follow? follow?)
             follow? (assoc :waveform-center-time nil))))
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

(defn- active-phrase [phrases]
  (let [index (:active-index @player-state)]
    (when (and (>= index 0)
               (< index (count phrases)))
      (nth phrases index))))

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

(defn- clamp [low high value]
  (min high (max low value)))

(defn- selected-resolution [waveform]
  (or (get-in waveform [:resolutions :fine])
      (some-> waveform :resolutions vals first)))

(defn- valid-peak? [peak]
  (and (sequential? peak)
       (= 2 (count peak))
       (js/Number.isFinite (first peak))
       (js/Number.isFinite (second peak))))

(defn- valid-waveform? [waveform]
  (let [resolution (selected-resolution waveform)
        peaks (:peaks resolution)
        window-seconds (:window_seconds resolution)]
    (and (map? waveform)
         (map? resolution)
         (pos? (or (:duration_seconds waveform) 0))
         (pos? (or window-seconds 0))
         (sequential? peaks)
         (seq peaks)
         (every? valid-peak? peaks))))

(defn- canvas-size [canvas]
  (let [width (max 320 (or (.-clientWidth canvas) 0) 640)
        height (max 80 (or (.-clientHeight canvas) 0) 96)]
    (when (not= (.-width canvas) width)
      (set! (.-width canvas) width))
    (when (not= (.-height canvas) height)
      (set! (.-height canvas) height))
    {:width width
     :height height}))

(defn- set-dataset! [element key value]
  (aset (.-dataset element) key (str value)))

(defn- remove-dataset! [element key]
  (js-delete (.-dataset element) key))

(defn- editor-mode? [mount]
  (= "true" (dataset-value mount "waveformEditor")))

(defn- selected-segment [segments]
  (let [selected-id (:selected-segment-id @player-state)]
    (some #(when (= selected-id (:id %)) %) segments)))

(defn- clear-selected-segment! [segments]
  (doseq [segment segments]
    (.. (:element segment) -classList (remove "is-selected-segment"))))

(defn- visible-window [duration center-time window-seconds]
  (let [duration (max 0 (or duration 0))
        window-seconds (max 1 (or window-seconds default-waveform-window-seconds))
        bounded-window (min duration window-seconds)
        half-window (/ bounded-window 2)
        max-start (max 0 (- duration bounded-window))
        window-start (if (pos? bounded-window)
                       (clamp 0 max-start (- center-time half-window))
                       0)
        window-end (min duration (+ window-start bounded-window))]
    {:start window-start
     :end window-end}))

(defn- click-time [canvas visible-start visible-end event]
  (let [rect (.getBoundingClientRect canvas)
        width (.-width rect)
        offset (- (.-clientX event) (.-left rect))
        ratio (if (pos? width)
                (clamp 0 1 (/ offset width))
                0)]
    (+ visible-start (* ratio (- visible-end visible-start)))))

(defn- millisecond-time [seconds]
  (/ (js/Math.round (* seconds 1000)) 1000))

(defn- segment-center [segment]
  (/ (+ (:start segment) (:end segment)) 2))

(defn- segment-x [width visible-start visible-end time]
  (if (> visible-end visible-start)
    (* width (/ (- time visible-start) (- visible-end visible-start)))
    0))

(defn- handle-container! [mount canvas]
  (or (.querySelector mount ".waveform-editor-handles")
      (let [container (.createElement js/document "div")]
        (.setAttribute container "class" "waveform-editor-handles")
        (.insertBefore mount container (.-nextSibling canvas))
        container)))

(defn- boundary-handle! [container boundary]
  (or (.querySelector container (str "[data-waveform-boundary='" boundary "']"))
      (let [handle (.createElement js/document "button")]
        (.setAttribute handle "type" "button")
        (.setAttribute handle "class" "waveform-boundary-handle is-selected-segment")
        (.setAttribute handle "data-waveform-boundary" boundary)
        (.setAttribute handle "aria-label" (str "Adjust segment " boundary))
        (.appendChild container handle)
        handle)))

(defn- position-boundary-handle! [handle segment boundary width visible-start visible-end]
  (let [time (if (= boundary "start") (:start segment) (:end segment))
        x (clamp 0 width (segment-x width visible-start visible-end time))]
    (set-dataset! handle "segmentId" (:id segment))
    (set-dataset! handle "segmentKind" (:kind segment))
    (set! (.. handle -style -left) (str x "px"))))

(defn- render-boundary-handles! [mount canvas segments visible-start visible-end]
  (if-let [segment (selected-segment segments)]
    (let [{:keys [width]} (canvas-size canvas)
          container (handle-container! mount canvas)]
      (set-dataset! mount "editorMode" true)
      (set-dataset! mount "selectedSegmentId" (:id segment))
      (.setAttribute container "data-selected-segment-id" (:id segment))
      (position-boundary-handle! (boundary-handle! container "start") segment "start" width visible-start visible-end)
      (position-boundary-handle! (boundary-handle! container "end") segment "end" width visible-start visible-end))
    (do
      (remove-dataset! mount "selectedSegmentId")
      (when-let [container (.querySelector mount ".waveform-editor-handles")]
        (set! (.-textContent container) "")))))

(defn- select-segment! [mount segments segment schedule-render!]
  (clear-selected-segment! segments)
  (swap! player-state assoc
         :selected-segment-id (:id segment)
         :waveform-center-time (segment-center segment)
         :follow? false)
  (.. (:element segment) -classList (add "is-selected-segment"))
  (set-dataset! mount "selectedSegmentId" (:id segment))
  (schedule-render!))

(defn- boundary-time [segment boundary]
  (if (= boundary "start")
    (:start segment)
    (:end segment)))

(defn- emit-boundary-edit! [mount canvas drag event]
  (let [visible-start (number-value (dataset-value mount "visibleStart"))
        visible-end (number-value (dataset-value mount "visibleEnd"))
        segment (:segment drag)
        boundary (:boundary drag)]
    (when (and visible-start visible-end segment boundary)
      (let [new-time (millisecond-time (click-time canvas visible-start visible-end event))
            detail #js {:segmentId (:id segment)
                        :segmentKind (:kind segment)
                        :boundary boundary
                        :oldTime (boundary-time segment boundary)
                        :newTime new-time}]
        (.dispatchEvent mount (js/CustomEvent. "waveform-edit"
                                               #js {:bubbles true
                                                    :detail detail}))))))

(defn- update-waveform-controls! [mount]
  (let [selected (:waveform-window-seconds @player-state)]
    (doseq [button (array-seq (.querySelectorAll mount "[data-waveform-window]"))]
      (.setAttribute button "aria-pressed"
                     (str (= (number-value (dataset-value button "windowSeconds"))
                             selected))))))

(defn- mark-waveform-fallback! [mount reason]
  (set-dataset! mount "waveformState" "fallback")
  (set-dataset! mount "waveformError" reason))

(defn- draw-segment-overlays! [context width height window-start window-end phrases active-id]
  (doseq [phrase phrases
          :let [start (:start phrase)
                end (:end phrase)]
          :when (and start end (< start window-end) (> end window-start))]
    (let [x (* width (/ (- (max start window-start) window-start)
                        (- window-end window-start)))
          segment-width (max 1 (* width (/ (- (min end window-end)
                                              (max start window-start))
                                           (- window-end window-start))))]
      (set! (.-fillStyle context)
            (if (= active-id (phrase-id phrase))
              "rgba(244, 185, 66, 0.24)"
              "rgba(15, 118, 110, 0.12)"))
      (.fillRect context x 0 segment-width height))))

(defn- draw-peaks! [context width height window-start window-end resolution]
  (let [peaks (:peaks resolution)
        seconds-per-peak (:window_seconds resolution)
        start-index (max 0 (js/Math.floor (/ window-start seconds-per-peak)))
        end-index (min (count peaks)
                       (js/Math.ceil (/ window-end seconds-per-peak)))
        bar-width (max 1 (/ width (max 1 (- end-index start-index))))
        center-y (/ height 2)
        amplitude (* height 0.42)]
    (set! (.-fillStyle context) "rgba(25, 23, 20, 0.58)")
    (doseq [index (range start-index end-index)
            :let [[minimum maximum] (nth peaks index)
                  min-value (clamp -1 1 (or minimum 0))
                  max-value (clamp -1 1 (or maximum 0))
                  x (* (- index start-index) bar-width)
                  y1 (- center-y (* max-value amplitude))
                  y2 (- center-y (* min-value amplitude))]]
      (.fillRect context x y1 (max 1 (- bar-width 1)) (max 1 (- y2 y1))))))

(defn- render-waveform! [mount canvas audio phrases waveform]
  (when-let [context (.getContext canvas "2d")]
    (let [{:keys [width height]} (canvas-size canvas)
          duration (or (:duration_seconds waveform)
                       (number-value (dataset-value mount "waveformDuration"))
                       (.-duration audio)
                       0)
          current-time (max 0 (or (.-currentTime audio) 0))
          window-seconds (:waveform-window-seconds @player-state)
          center-time (if (:follow? @player-state)
                        current-time
                        (or (:waveform-center-time @player-state) current-time))
          {:keys [start end]} (visible-window duration center-time window-seconds)
          active-id (phrase-id (active-phrase phrases))
          resolution (selected-resolution waveform)]
      (set-dataset! mount "waveformState" "ready")
      (set-dataset! mount "followMode" (:follow? @player-state))
      (set-dataset! mount "windowSeconds" window-seconds)
      (set-dataset! mount "activeSegmentId" active-id)
      (set-dataset! mount "visibleStart" (.toFixed start 3))
      (set-dataset! mount "visibleEnd" (.toFixed end 3))
      (update-waveform-controls! mount)
      (.clearRect context 0 0 width height)
      (draw-segment-overlays! context width height start end phrases active-id)
      (draw-peaks! context width height start end resolution)
      (set! (.-fillStyle context) "rgba(15, 118, 110, 0.95)")
      (let [playhead-x (if (> end start)
                         (* width (/ (- current-time start) (- end start)))
                         0)]
        (.fillRect context (clamp 0 width playhead-x) 0 2 height))
      (when (editor-mode? mount)
        (render-boundary-handles! mount canvas phrases start end)))))

(defn- waveform-control-button [label attrs]
  (let [button (.createElement js/document "button")]
    (.setAttribute button "type" "button")
    (doseq [[key value] attrs]
      (.setAttribute button key value))
    (set! (.-textContent button) label)
    button))

(defn- mount-waveform-controls! [mount]
  (let [controls (.createElement js/document "div")
        window-group (.createElement js/document "div")
        recenter (waveform-control-button "Recenter" {"data-waveform-recenter" ""})]
    (.setAttribute controls "class" "waveform-controls")
    (.setAttribute window-group "class" "waveform-window-controls")
    (doseq [{:keys [seconds label]} waveform-window-presets]
      (.appendChild window-group
                    (waveform-control-button label {"data-waveform-window" ""
                                                    "data-window-seconds" (str seconds)})))
    (.appendChild controls window-group)
    (.appendChild controls recenter)
    (.appendChild mount controls)
    controls))

(defn- install-waveform! [audio phrases follow-button scroll-active!]
  (when-let [mount (.querySelector js/document "#episode-waveform")]
    (let [url (dataset-value mount "waveformUrl")]
      (when (seq url)
        (set-dataset! mount "waveformState" "loading")
        (set-dataset! mount "followMode" true)
        (when (editor-mode? mount)
          (set-dataset! mount "editorMode" true))
        (let [controls (mount-waveform-controls! mount)
              canvas (.createElement js/document "canvas")
              waveform-state (atom nil)
              render-frame (atom nil)
              boundary-drag (atom nil)]
          (.setAttribute canvas "tabindex" "0")
          (.setAttribute canvas "role" "slider")
          (.setAttribute canvas "aria-label" "Audio waveform")
          (.appendChild mount canvas)
          (letfn [(render-now! []
                    (when-let [waveform @waveform-state]
                      (render-waveform! mount canvas audio phrases waveform)))
                  (schedule-render! []
                    (when (and (nil? @render-frame)
                               (.-requestAnimationFrame js/window))
                      (reset! render-frame
                              (js/window.requestAnimationFrame
                               (fn [_]
                                 (reset! render-frame nil)
                                 (render-now!))))))
                  (sync-local! []
                    (sync-to-audio! audio phrases scroll-active! false)
                    (schedule-render!))
                  (seek-from-waveform! [seconds]
                    (let [duration (or (some-> @waveform-state :duration_seconds)
                                       (number-value (dataset-value mount "waveformDuration"))
                                       (.-duration audio)
                                       0)
                          target (clamp 0 duration seconds)]
                      (set-follow! follow-button false)
                      (swap! player-state assoc :waveform-center-time target)
                      (set! (.-currentTime audio) target)
                      (sync-local!)))
                  (toggle-play! []
                    (if (.-paused audio)
                      (when-let [play-result (.play audio)]
                        (when (.-catch play-result)
                          (.catch play-result (fn [_]))))
                      (.pause audio)))]
            (.addEventListener
             controls
             "click"
             (fn [event]
               (let [target (.-target event)]
                 (when-let [window-button (when target (.closest target "[data-waveform-window]"))]
                   (swap! player-state assoc
                          :waveform-window-seconds
                          (or (number-value (dataset-value window-button "windowSeconds"))
                              default-waveform-window-seconds))
                   (schedule-render!))
                 (when (and target (.closest target "[data-waveform-recenter]"))
                   (set-follow! follow-button true)
                   (sync-to-audio! audio phrases scroll-active! true)
                   (schedule-render!)))))
            (.addEventListener
             canvas
             "click"
             (fn [event]
               (let [visible-start (number-value (dataset-value mount "visibleStart"))
                     visible-end (number-value (dataset-value mount "visibleEnd"))]
                 (when (and visible-start visible-end)
                   (seek-from-waveform! (click-time canvas visible-start visible-end event))))))
            (.addEventListener
             mount
             "mousedown"
             (fn [event]
               (when-let [handle (some-> event .-target (.closest "[data-waveform-boundary]"))]
                 (.preventDefault event)
                 (let [segment-id (dataset-value handle "segmentId")
                       boundary (dataset-value handle "waveformBoundary")]
                   (when-let [segment (some #(when (= segment-id (:id %)) %) phrases)]
                     (reset! boundary-drag {:segment segment
                                            :boundary boundary}))))))
            (.addEventListener
             js/window
             "mouseup"
             (fn [event]
               (when-let [drag @boundary-drag]
                 (reset! boundary-drag nil)
                 (emit-boundary-edit! mount canvas drag event)
                 (schedule-render!))))
            (.addEventListener
             canvas
             "keydown"
             (fn [event]
               (let [key (.-key event)]
                 (when (#{" " "Enter" "ArrowLeft" "ArrowRight"} key)
                   (.preventDefault event)
                   (case key
                     (" " "Enter") (toggle-play!)
                     "ArrowLeft" (seek-from-waveform!
                                  (- (.-currentTime audio)
                                     (max 5 (/ (:waveform-window-seconds @player-state) 20))))
                     "ArrowRight" (seek-from-waveform!
                                   (+ (.-currentTime audio)
                                      (max 5 (/ (:waveform-window-seconds @player-state) 20))))
                     nil)))))
            (-> (js/fetch url)
                (.then (fn [response]
                         (if (.-ok response)
                           (.json response)
                           (throw (js/Error. "waveform request failed")))))
                (.then (fn [json]
                         (let [waveform (js->clj json :keywordize-keys true)]
                           (if (valid-waveform? waveform)
                             (do
                               (reset! waveform-state waveform)
                               (schedule-render!))
                             (mark-waveform-fallback! mount "malformed")))))
                (.catch (fn [_]
                          (mark-waveform-fallback! mount "load-failed"))))
            (when (.-ResizeObserver js/window)
              (let [observer (js/ResizeObserver. (fn [_] (schedule-render!)))]
                (.observe observer canvas)))
            {:render! schedule-render!
             :select-segment! (fn [segment]
                                (when (editor-mode? mount)
                                  (select-segment! mount phrases segment schedule-render!)))}))))))

(defn- install-player! []
  (let [audio (.querySelector js/document "#episode-audio")
        transcript-root (.querySelector js/document ".transcript-shell")
        follow-button (.querySelector js/document "[data-follow-toggle]")
        phrases (mapv phrase-record (query-all ".phrase"))
        waveform-api (atom nil)
        suppress-scroll-pause? (atom false)
        scroll-pause-timer (atom 0)]
    (when (and audio transcript-root (seq phrases))
      (reset! player-state {:active-index -1
                            :follow? true
                            :waveform-window-seconds default-waveform-window-seconds
                            :waveform-center-time nil
                            :selected-segment-id nil})
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
                 (sync-to-audio! audio phrases scroll-active-into-view! should-scroll?)
                 (when-let [render! (:render! @waveform-api)]
                   (render!))))
              (seek! [seconds]
                (seek-to! audio phrases follow-button scroll-active-into-view! seconds))]
        (.addEventListener
         transcript-root
         "click"
         (fn [event]
           (let [target (.-target event)
                 phrase (when target (.closest target ".phrase"))]
             (if phrase
               (let [segment (some #(when (= phrase (:element %)) %) phrases)]
                 (if-let [select! (:select-segment! @waveform-api)]
                   (when segment
                     (set! (.-currentTime audio) (max 0 (or (number-value (dataset-value phrase "start")) 0)))
                     (sync-to-audio! audio phrases scroll-active-into-view! false)
                     (select! segment))
                   (seek! (number-value (dataset-value phrase "start")))))
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
        (.addEventListener audio "timeupdate" #(sync! true))
        (.addEventListener
         audio
         "play"
         (fn [_]
           (set-follow! follow-button true)
           (sync! true)))
        (.addEventListener
         audio
         "seeked"
         (fn [_]
           (sync! true)))
        (.addEventListener audio "loadedmetadata" #(sync! false))
        (update-follow-button! follow-button)
        (reset! waveform-api
                (install-waveform! audio phrases follow-button scroll-active-into-view!))
        (sync! false)))))

(defn init! []
  (if (= "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" install-player!)
    (install-player!)))
