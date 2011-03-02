(ns leiningen.cljs
  (:use [cljs.watch])
  (:require [clojure.java.io :as io]
            [cljs.stitch :as stitch]
            [leiningen.install]
            [leiningen.jar])
  (:use [clojure.pprint])
  (:require [robert.hooke :as hooke]
            [leiningen.jar])
  (:require [leiningen.compile :as compile]
            [clojure.string :as string]
            [lancet])
  (:require [leiningen.pom :as pom]
            [clojure.java.io :as io])
  (:use [leiningen.deps :only [deps]])
  (:import (java.util.jar Manifest JarEntry JarOutputStream)
           (java.util.regex Pattern)
           (java.util.jar JarFile)
           (java.io BufferedOutputStream FileOutputStream
                    ByteArrayInputStream)))

(defn parse-project [path-or-project]
  (if (map? path-or-project)
    path-or-project
    (let [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.FileReader. path-or-project))]
      (read rdr))))

;; For REPL use
(defn parse-cljs-opts
  ([project-path]
     (:cljs (parse-project project-path)))
  ([] (parse-cljs-opts "project.clj")))

;; Stolen from marginalia:
(defn ls
  [path]
  (let [file (java.io.File. path)]
    (if (.isDirectory file)
      (seq (.list file))
      (when (.exists file)
        [path]))))

(defn mkdir [path]
  (.mkdirs (io/file path)))

(defn ensure-directory!
  [path]
  (when-not (ls path)
    (mkdir path)))

(defn cljs-watch [project args]
  (let [cljs-opts (merge {:source-path "src/cljs"
                          :output-path "resources/public/js"}
                         (:cljs project)
                         (apply hash-map args))
        src-path (:source-path cljs-opts)
        out-path (:output-path cljs-opts)]
    (when-not (ls out-path)
      (println "Output directory" out-path "not found, creating.")
      (ensure-directory! out-path))
    (println "Watching" src-path "for changes...")
    (start-watch src-path out-path)))

(def idt "  ")

(defn cljs-compile [project]
  (stitch/stitch-project "./project.clj"))

;; ------------ lein jar stuff ---------------

(defn- read-resource [resource-name]
  (-> (.getContextClassLoader (Thread/currentThread))
      (.getResourceAsStream resource-name)
      (slurp)))

(defn- read-bin-template [system]
  (case system
        :unix (read-resource "script-template")
        :windows (read-resource "script-template.bat")))

(defn unix-path [path]
  (.replace path "\\" "/"))

(defn windows-path [path]
  (.replace path "/" "\\"))

(defn local-repo-path
  ([group name version]
     (local-repo-path {:group group :name name :version version}))
  ([{:keys [group name version]}]
     (unix-path (format
                 "$HOME/.m2/repository/%s/%s/%s/%s-%s.jar"
                 (.replace group "." "/") name version name version))))

(defn- script-classpath-for [project deps-fileset system]
  (let [deps (when deps-fileset
               (-> deps-fileset
                   (.getDirectoryScanner lancet/ant-project)
                   (.getIncludedFiles)))
        unix-paths (conj (for [dep deps]
                           (unix-path (format "$HOME/.m2/repository/%s" dep)))
                         (local-repo-path project))]
    (case system
          :unix (string/join ":" unix-paths)
          :windows (string/join ";" (for [path unix-paths]
                                      (windows-path
                                       (.replace path "$HOME"
                                                 "%USERPROFILE%")))))))

(defn- shell-wrapper-name [project]
  (get-in project [:shell-wrapper :bin]
          (format "bin/%s" (:name project))))

(defn- shell-wrapper-contents [project bin-name main deps-fileset system]
  (let [file-name (case system
                        :unix bin-name
                        :windows (format "%s.bat" bin-name))
        bin-file (io/file file-name)]
    (format (if (.exists bin-file)
              (slurp bin-file)
              (read-bin-template system))
            (script-classpath-for project deps-fileset system)
            main (:version project))))

(defn- shell-wrapper-filespecs [project deps-fileset]
  (when (:shell-wrapper project)
    (let [main (or (:main (:shell-wrapper project)) (:main project))
          bin-name (shell-wrapper-name project)
          read-bin #(shell-wrapper-contents
                     project bin-name main deps-fileset %)]
      [{:type :bytes
        :path bin-name
        :bytes (.getBytes (read-bin :unix))}
       {:type :bytes
        :path (format "%s.bat" bin-name)
        :bytes (.getBytes (read-bin :windows))}])))

(def default-manifest
     {"Created-By" (str "Leiningen " (System/getenv "LEIN_VERSION"))
      "Built-By" (System/getProperty "user.name")
      "Build-Jdk" (System/getProperty "java.version")})

(defn make-manifest [project & [extra-entries]]
  (Manifest.
   (ByteArrayInputStream.
    (.getBytes
     (reduce (fn [manifest [k v]]
               (str manifest "\n" k ": " v))
             "Manifest-Version: 1.0"
             (merge default-manifest (:manifest project)
                    (when (:shell-wrapper project)
                      {"Leiningen-shell-wrapper" (shell-wrapper-name project)})
                    (when-let [main (:main project)]
                      {"Main-Class" (.replaceAll (str main) "-" "_")})))))))

(defn manifest-map [manifest]
  (let [attrs (.getMainAttributes manifest)]
    (zipmap (map str (keys attrs)) (vals attrs))))

(defn skip-file? [file relative-path patterns]
  (or (.isDirectory file)
      (re-find #"^\.?#" (.getName file))
      (re-find #"~$" (.getName file))
      (some #(re-find % relative-path) patterns)))

(defmulti copy-to-jar (fn [project jar-os spec] (:type spec)))

(defn- trim-leading-str [s to-trim]
  (.replaceAll s (str "^" (Pattern/quote to-trim)) ""))

(defmethod copy-to-jar :path [project jar-os spec]
  (let [root (str (unix-path (:root project)) \/)
        noroot  #(trim-leading-str (unix-path %) root)
        cljs-src (noroot (:source-path (:cljs project)))
        resources (noroot (:resources-path project))]
    (doseq [child (file-seq (io/file (:path spec)))]
      (let [path (reduce trim-leading-str (unix-path (str child))
                         [root resources cljs-src "/"])]
        (when-not (skip-file? child path (:jar-exclusions project))
          (.putNextEntry jar-os (doto (JarEntry. path)
                                  (.setTime (.lastModified child))))
          (io/copy child jar-os))))))

(defmethod copy-to-jar :bytes [project jar-os spec]
  (.putNextEntry jar-os (JarEntry. (:path spec)))
  (io/copy (ByteArrayInputStream. (:bytes spec)) jar-os))

;; TODO: hacky; needed for conditional :resources-path below
(defmethod copy-to-jar nil [project jar-os spec])

(defn write-jar [project out-filename filespecs]
  (let [manifest (make-manifest project)]
    (with-open [jar-os (-> out-filename
                           (FileOutputStream.)
                           (BufferedOutputStream.)
                           (JarOutputStream. manifest))]
      (doseq [filespec filespecs]
        (copy-to-jar project jar-os filespec)))))

(defn get-default-jar-name [project]
  (or (:jar-name project)
      (str (:name project) "-" (:version project) ".jar")))

(defn get-jar-filename
  ([project jar-name]
     (let [target-dir (:target-dir project)]
       (.mkdirs (io/file target-dir))
       (str target-dir "/" jar-name)))
  ([project] (get-jar-filename project (get-default-jar-name project))))

(defn get-default-uberjar-name [project]
  (or (:uberjar-name project)
      (str (:name project) \- (:version project) "-standalone.jar")))

(defn- filespecs [project deps-fileset]
  (concat
   [{:type :bytes
     :path (format "META-INF/maven/%s/%s/pom.xml"
                   (:group project)
                   (:name project))
     :bytes (pom/make-pom project)}
    {:type :bytes
     :path (format "META-INF/maven/%s/%s/pom.properties"
                   (:group project)
                   (:name project))
     :bytes (pom/make-pom-properties project)}
    (when (and (:resources-path project)
               (.exists (io/file (:resources-path project))))
      {:type :path :path (:resources-path project)})
    {:type :path :path (str (:root project) "/project.clj")}]
   [{:type :path :path (:source-path (:cljs project))}]
   (shell-wrapper-filespecs project deps-fileset)))

(defn extract-jar
  "Unpacks jar-file into target-dir. jar-file can be a JarFile
  instance or a path to a jar file on disk."
  [jar-file target-dir]
  (let [jar (if (isa? jar-file JarFile)
              jar-file
              (JarFile. jar-file true))
        entries (enumeration-seq (.entries jar))
        target-file #(file target-dir (.getName %))]
    (doseq [entry entries :when (not (.isDirectory entry))
            :let [f (target-file entry)]]
      (.mkdirs (.getParentFile f))
      (io/copy (.getInputStream jar entry) f))))

(defn jar
  "Create a $PROJECT-$VERSION.jar file containing project's source files as well
as .class files if applicable. If project.clj contains a :main key, the -main
function in that namespace will be used as the main-class for executable jar."
  ([project jar-name]
     (binding [compile/*silently* true
               deps (memoize deps)]
       (when (zero? (compile/compile project))
         (let [jar-path (get-jar-filename project jar-name)
               deps-fileset (deps project)]
           (write-jar project jar-path (filespecs project deps-fileset))
           (println "Created" jar-path)
           jar-path))))
  ([project] (jar project (get-default-jar-name project))))

(defn cljs-jar [project & args]
  (let [cljs-opts (:cljs project)
        source-path (:source-path cljs-opts)
        test-path (:test-path cljs-opts)]
    (if (or source-path
               test-path)
      (jar project)
      (println "Couldn't find a :source-path or :test-path in your project's cljs opts."))))

;; ------------------------------------------

(defn cljs-install [project]
  (binding [jar cljs-jar]
    (leiningen.install/install project)))

(defn cljs
  ^{:doc "cljs compiler interface"}
  [project & args]
  (println (:cljs project))
  (let [cmd (first args)
        args (rest args)
        cljs-opts (parse-cljs-opts project)
        source-path (:source-path cljs-opts)
        test-path (:test-path cljs-opts)]
    (case
     cmd
     "watch"   (cljs-watch project args)
     "compile" (cljs-compile project)
     "jar"     (cljs-jar project args)
     "install" (cljs-install project)
     (if (or (not cmd) (empty? cmd))
       (do
         (println "Usage: lein cljs <command>")
         (println "Commands:")
         (println idt "watch   - Automatic recompilation of cljs files")
         (println idt "compile - Compile cljs source into javascript")
         (println idt "jar     - Jar up cljs files")
         (println idt "install - Install jarred cljs files into your repo"))
       (println "no matching command")))))
