(ns clj-stacktrace.core
  (:require [clojure.contrib.str-utils :as str]
            [clj-stacktrace.utils :as utils]))

(defn- clojure-code?
  "Returns true if the filename is non-null and indicates a clj source file."
  [class-name file]
  (or (utils/re-match? #"^user" class-name)
      (and file (utils/re-match? #"\.clj$" file))))

(defn- clojure-ns
  "Returns the clojure namespace name implied by the bytecode class name."
  [class-name]
  (str/re-gsub #"_" "-" (utils/re-get #"([^$]+)\$" class-name 1)))

(def alpha-punc-subs
  [[#"_QMARK_" "?"]
   [#"_BANG_"  "!"]
   [#"_PLUS_"  "+"]
   [#"_GT_"    ">"]
   [#"_LT_"    "<"]
   [#"_EQ_"    "="]
   [#"_STAR_"  "*"]
   [#"_SLASH_" "/"]
   [#"_"       "-"]])

(defn- clojure-fn
  "Returns the clojure function name implied by the bytecode class name."
  [class-name]
  (reduce
    (fn [base-name [alpha-punc punc]] (str/re-gsub alpha-punc punc base-name))
    (utils/re-without #"(^[^$]+\$)|(__\d+(\$[^$]+)*$)" class-name)
    alpha-punc-subs))

(defn- clojure-annon-fn?
  "Returns true if the bytecode class name implies an anonymous inner fn."
  [class-name]
  (utils/re-match? #"\$fn__" class-name))

(defn parse-trace-elem
  "Returns a map of information about the java trace element.
  All returned maps have the keys:
  :file      String of source file name. 
  :line      Number of source line number of the enclosing form.
  Additionally for elements from Java code:
  :java      true, to indicate a Java elem. 
  :class     String of the name of the class to which the method belongs.
  Additionally for elements from Clojure code:
  :clojure   true, to inidcate a Clojure elem. 
  :ns        String representing the namespace of the function.
  :fn        String representing the name of the enclosing var for the function. 
  :annon-fn  true iff the function is an anonymous inner fn."
  [elem]
  (let [class-name (.getClassName elem)
        file       (.getFileName  elem)
        line       (let [l (.getLineNumber elem)] (if (> l 0) l))
        parsed     {:file file :line line}]
    (if (clojure-code? class-name file)
      (assoc parsed
        :clojure true
        :ns       (clojure-ns class-name)
        :fn       (clojure-fn class-name)
        :annon-fn (clojure-annon-fn? class-name))
      (assoc parsed
        :java true
        :class class-name
        :method (.getMethodName elem)))))

(defn parse-trace-elems
  "Returns a seq of maps providing usefull information about the java stack
  trace elements. See parse-trace-elem."
  [elems]
  (map parse-trace-elem elems))

(defn- trim-redundant
  "Returns the portion of the tail of causer-elems that is not duplicated in 
  the tail of caused-elems. This corresponds to the \"...26 more\" that you
  see at the bottom of regular trace dumps."
  [causer-parsed-elems caused-parsed-elems]
  (loop [rcauser-parsed-elems (reverse causer-parsed-elems)
         rcaused-parsed-elems (reverse caused-parsed-elems)]
    (if-let [rcauser-bottom (first rcauser-parsed-elems)]
      (if (= rcauser-bottom (first rcaused-parsed-elems))
        (recur (next rcauser-parsed-elems) (next rcaused-parsed-elems))
        (reverse rcauser-parsed-elems)))))

(defn- parse-cause-exception
  "Like parse-exception, but for causing exceptions. The returned map has all
  of the same keys as the map returned by parse-exception, and one added one:
  :trimmed-elems  A subset of :trace-elems representing the portion of the
                  top of the stacktrace not shared with that of the caused
                  exception."
  [causer-e caused-parsed-elems]
  (let [parsed-elems (parse-trace-elems (.getStackTrace causer-e))
        base {:class         (class causer-e)
              :message       (.getMessage causer-e)
              :trace-elems   parsed-elems
              :trimmed-elems (trim-redundant parsed-elems caused-parsed-elems)}]
    (if-let [cause (.getCause causer-e)]
      (assoc base :cause (parse-cause-exception cause parsed-elems))
      base)))

(defn parse-exception
  "Returns a Clojure map providing usefull informaiton about the exception.
  The map has keys
  :class        Class of the exception.
  :message      Regular exception message string.
  :trace-elems  Parsed stack trace elems, see parse-trace-elem.
  :cause        See parse-cause-exception."
  [e]
  (let [parsed-elems (parse-trace-elems (.getStackTrace e))
        base {:class       (class e)
              :message     (.getMessage e)
              :trace-elems parsed-elems}]
    (if-let [cause (.getCause e)]
      (assoc base :cause (parse-cause-exception cause parsed-elems))
      base)))