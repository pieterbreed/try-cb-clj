(ns try-cb-clj.core
  (:require [clojure.tools.logging :as log])

  (:import [com.couchbase.client.java CouchbaseCluster Bucket]
           [com.couchbase.client.java.query N1qlQuery N1qlQueryResult N1qlQueryRow AsyncN1qlQueryResult AsyncN1qlQueryRow]
           [com.couchbase.client.java.document Document JsonDocument JsonArrayDocument JsonLongDocument JsonBooleanDocument]
           [com.couchbase.client.java.auth ClassicAuthenticator]
           [com.couchbase.client.java.env DefaultCouchbaseEnvironment]
           [com.couchbase.client.java.document.json JsonObject JsonArray JsonNull]
           [rx Observer Observable Subscriber]
           [java.util.concurrent TimeUnit]))


(defn connect [^String str]
  (CouchbaseCluster/fromConnectionString str))


(defn disconnect [^CouchbaseCluster cluster]
  (.disconnect cluster))


(defn open-bucket
  ([^CouchbaseCluster cluster name]
   (-> cluster
       (.openBucket name)))

  ([^CouchbaseCluster cluster name password]
   (-> cluster
       (.authenticate (-> (ClassicAuthenticator.)
                          (.bucket name password)))
       (.openBucket name password))))


(defn create-n1qlprimary-index [^Bucket bucket]
  (when bucket
    (-> bucket
        (.bucketManager)
        (.createN1qlPrimaryIndex true false))))


(defprotocol ClojureToJava
  "클로저 자료형을 자바자료형으로
  변환한다"
  (->java [o]))

(extend-protocol ClojureToJava
  clojure.lang.IPersistentMap
  (->java [o]
    (reduce (fn [jo [k v]]
              (.put jo (name k) (->java v)))
            (JsonObject/empty) o))


  clojure.lang.IPersistentCollection
  (->java [o]
    (reduce (fn [arry v]
              (.add arry (->java v)))
            (JsonArray/empty) o))


  java.util.Date
  (->java [o] (.getTime o))


  java.lang.String
  (->java [o]
    (let [s (-> o .trim)]
      (if (= 0 (.length s))
        JsonNull/INSTANCE s)))


  java.lang.Object
  (->java [o] o)

  nil
  (->java [o] JsonNull/INSTANCE))

(defn to-java [o]
  (->java o))


(defprotocol JavaToClojure
  "프로토콜 정의
  자바객체를 클로저 자료형으로 변환"
  (->clj [o]))

(extend-protocol JavaToClojure

  AsyncN1qlQueryRow
  (->clj [o]
    (->clj (.value o)))

  N1qlQueryRow
  (->clj [o]
    (->clj (.value o)))

  Document
  (->clj [o]
    (->clj (.content o)))

  JsonArray
  (->clj [o]
    (vec (map ->clj (.toList o))))

  JsonObject
  (->clj [o]>
    (reduce (fn [m k]
              (assoc m (keyword k) (->clj (.get o k))))
            {} (.getNames o)))

  java.util.Map
  (->clj [o]
    (reduce (fn [m [^String k v]]
              (assoc m (keyword k) (->clj v)))
            {} (.entrySet o)))

  java.util.List
  (->clj [o]
    (vec (map ->clj o)))

  java.lang.Object
  (->clj [o] o)

  nil
  (->clj [o] nil))


(defprotocol ICas
  (with-cas [this]))

(extend-protocol ICas
  Document
  (with-cas [o]
    {:cas (str (.cas o))
     :id (.id o)
     :expiry (.expiry o)
     :token (.mutationToken o)
     :value (to-clj o)})

  java.lang.Object
  (with-cas [o] o))


(defn to-clj 
  ([o]
   (->clj o))

  ([o with-cas?]
   (if with-cas?
     (with-cas o)
     (to-clj o))))



(declare to-map
         to-flat
         single!
         first!)


(defprotocol IBucket
  "프로토콜 정의
  버킷에서 사용할 도큐먼트를 생성, 가져오기"
  (create-doc [this id cas])
  (get-doc    [this bucket as-type with-cas]))


(defprotocol IQuery
  (simple-query [this] [this args]))


(defprotocol IMetric
  (get-metrics [this]))



(extend-protocol IQuery
  Observable
  (simple-query [this args]
    (let [is-block? (:block args false)
          with-metric? (:with-metric args false)
          rows (if is-block?
                 (for [i (-> this
                             (to-flat (fn [x] (.rows x)))
                             (.timeout 1 TimeUnit/SECONDS)
                             (.toBlocking)
                             (.getIterator)
                             (iterator-seq))]
                   (to-clj i))

                 (-> this
                     (to-flat (fn [x] (.rows x)))))]

      (if with-metric?
        (assoc (get-metrics this) :results rows)
        rows)))


  N1qlQueryResult
  (simple-query [this args]

    (let [with-metric? (:with-metric args false)
          rows (for [i (.allRows this)]
                 (to-clj i))]

      (if with-metric?
        (assoc (get-metrics this) :results rows)
        rows))))


(extend-protocol IMetric

  Observable
  (get-metrics [this]
    {:resultId (-> (to-map this (fn [x] (.requestId x)))
                   (single!))
     :errors (-> (to-flat this (fn [x] (.errors x)))
                 (single! []))
     :status (-> (to-flat this (fn [x] (.status x)))
                 (single!))
     :metrics (-> (to-flat this (fn [x] (.info x)))
                  (single!)
                  (.asJsonObject)
                  (to-clj))})

  N1qlQueryResult
  (get-metrics [this]
    {:requestId (.requestId this)
     :errors (to-clj (.errors this))
     :status (.status this)
     :metrics (to-clj (.asJsonObject (.info this)))}))


(extend-protocol IBucket

  java.lang.Object
  (get-doc [this bucket as-type with-cas]
    (case as-type
      :long (to-clj (.get bucket this JsonLongDocument) with-cas)
      :array (to-clj (.get bucket this JsonArrayDocument) with-cas)
      (to-clj (.get bucket this) with-cas)))


  clojure.lang.IPersistentMap
  (create-doc [this id cas]
    (let [content (to-java this)]
      (if (nil? cas)
        (JsonDocument/create id content)
        (JsonDocument/create id content cas))))


  clojure.lang.IPersistentVector
  (create-doc [this id cas]
    (let [content (to-java this)]
      (if (nil? cas)
        (JsonArrayDocument/create id content)
        (JsonArrayDocument/create id content cas))))


  java.lang.Long
  (create-doc [this id cas]
    (let [content (to-java this)]
      (if (nil? cas)
        (JsonLongDocument/create id content)
        (JsonLongDocument/create id content cas)))))



(defmacro async-bucket [binding & body]
  "couchbase 매크로
   async bucket를 사용하게 함
   예) (async-bucket [bc *bucket*]
         (-> (counter bc \"user::id\" 1 1)
             (to-map)))"

  `(let [~(first binding) (.async ~(second binding))]
     (do ~@body)))


;;; couchbase 메서드

(defn insert!
  ([bucket doc]
   (insert! bucket doc nil))

  ([bucket doc {:keys [with-cas] :or {with-cas false} }]
   (insert! bucket (.toString (java.util.UUID/randomUUID)) doc {:with-cas with-cas}))

  ([bucket id doc {:keys [with-cas] :or {with-cas false}}]
   (->
    (->> (create-doc doc id nil)
         (.insert bucket)) 
    (to-clj with-cas))))


(defn upsert! [bucket id doc & [{:keys [with-cas] :or {with-cas false} }]]
  (->
   (->> (create-doc doc id nil)
        (.upsert bucket))
   (to-clj with-cas)))


(defn replace!
  ([bucket id doc]
   (replace! bucket id doc nil))

  ([bucket id doc {:keys [with-cas] :or {with-cas false}}]
   (->
    (->> (create-doc doc id nil)
         (.replace bucket))
    (to-clj with-cas)))

  ([bucket id doc cas {:keys [with-cas] :or {with-cas false}}]
   (-> 
    (->> (create-doc doc id cas)
         (.replace bucket))
    (to-clj with-cas))))


(defn get! [bucket doc & [{:keys [as-type with-cas] :or {with-cas false}}]]
  "JsonLongDocument로 저장된 경우
   예) (get! *bucket* \"hello\" :long)
   없을 경우 JsonDocument로 가져온다"
  (get-doc doc bucket as-type with-cas))


(defn get-as-long 
  ([bucket id]
   (get-doc id bucket :long false))

  ([bucket id {:keys [with-cas]}]
   (get-doc id bucket :long with-cas)))


(defn get-as-array 
  ([bucket id]
   (get-doc id bucket :array false))

  ([bucket id {:keys [with-cas]}]
   (get-doc id bucket :array with-cas)))


(defn remove! [bucket id]
  (do
    (.remove bucket id)
    true))


(defn counter [bucket id a b {:keys [with-cas] :or {with-cas false}}]
  (-> (.counter bucket id a b)
      (to-clj with-cas)))


(defn to-map
  ([^Observable ob]
   (-> ob
       (.map (reify rx.functions.Func1
               (call [this doc]
                 (to-clj doc))))))


  ([^Observable ob caller]
   (-> ob
       (.map (reify rx.functions.Func1
               (call [this doc]
                 (caller (to-clj doc))))))))



(defn to-flat [^Observable ob caller]
  (-> ob
      (.flatMap (reify rx.functions.Func1
                  (call [this doc]
                    (caller doc))))))


(defn single!
  ([^Observable ob val]
   (-> ob
       (.timeout 1 TimeUnit/SECONDS)
       (.toBlocking)
       (.singleOrDefault val)
       to-clj))

  ([^Observable ob]
   (single! ob nil)))



(defn first!
  ([^Observable ob]
   (-> ob
       (.timeout 1 TimeUnit/SECONDS)
       (.toBlocking)
       (.first)
       to-clj)))



(defn query
  ([bucket [str & params] & [{:keys [with-metric block] :or {with-metric false block false}}]]
   (let [result (->> (if (nil? params)
                       (N1qlQuery/simple str)
                       (N1qlQuery/parameterized str (to-java params)))
                     (.query bucket))]
     (simple-query result {:block block
                           :with-metric with-metric}))))


(defn subscribe
  ([^Observable ob]
   (.subscribe ob))


  ([^Observable ob & args]
   (let [l (apply hash-map args)
         on-completed (:on-completed l)
         on-error (:on-error l)
         on-next (:on-next l)]

     (.subscribe ob
                 (proxy [Subscriber] []
                   (onCompleted []
                     (log/debug "completed!...")
                     (when (fn? on-completed)
                       (on-completed)))

                   (onError [throwable]
                     (log/error "error ..." throwable)
                     (if (fn? on-error)
                       (on-error throwable)
                       (throw throwable)))

                   (onNext [o]
                     (log/debug "next ..." o)
                     (when (fn? on-next)
                       (on-next o))))))))
